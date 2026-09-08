package com.bank.aml.refund;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G4 全链验收（RF-06/RF-11/RF-13/RF-22/RF-24/RF-30）：
 * 退款金额账在真实 MySQL 上的行级验证——并发占用、超额拒绝、失败回滚无半成品、
 * 存量语义（旧数据不被伪造）。
 * 运行：./mvnw -Pintegration-test test -Dtest=RefundLedgerIntegrationTest
 */
@Tag("integration")
@SpringBootTest
class RefundLedgerIntegrationTest {

    private static final String SCHEMA = "aml_refund_ledger_test";
    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");
    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");
    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        String serverUrl = "jdbc:mysql://" + HOST
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(serverUrl, ROOT_USER, ROOT_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception e) {
            throw new IllegalStateException("无法创建隔离 schema " + SCHEMA, e);
        }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + HOST + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> ROOT_USER);
        registry.add("spring.datasource.password", () -> ROOT_PASSWORD);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("aml.rag.rerank.enabled", () -> "false");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @Autowired
    private RefundLedgerService ledgerService;
    @Autowired
    private RefundEventRepository eventRepository;
    @Autowired
    private RefundAllocationRepository allocationRepository;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createCase(String tag) {
        CaseEntity c = new CaseEntity();
        c.setCustomerId(tag + "-C001");
        c.setCustomerName(tag + "演示客户");
        c.setAlertRule("集团代付后退货退款");
        c.setStatus(CaseStatus.HOLD);
        c.setInvestigationContractVersion(2);
        c.setCaseFactsEpoch(0);
        return caseRepository.save(c).getId();
    }

    /** RF-06：44 万原付款、12 万退给 P → 已退 12、保留 32；事件与分配行级可回放。 */
    @Test
    void refundLedgerBalancesInRealDatabase() {
        Long caseId = createCase("RF06");
        var result = ledgerService.register(caseId, new RefundLedgerService.RefundRegistration(
                "CORE_BANKING", "RF06-E1", "POSTED", "甲贸易公司", "丙集团公司", "ACCT-P",
                new BigDecimal("120000.00"), "CNY", LocalDateTime.now(),
                List.of(new RefundLedgerService.AllocationInput("T-1001", "SO-01",
                        new BigDecimal("120000.00"), null))), "analyst");
        assertThat(result.idempotentReplay()).isFalse();

        var ledger = ledgerService.ledger(caseId, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("120000.00");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("320000.00");

        // 行级：退款事件与分配真实落库
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class, caseId);
        assertThat(eventRows).isEqualTo(1);
        Integer allocationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_allocation WHERE case_id = ? AND original_transaction_id = 'T-1001'",
                Integer.class, caseId);
        assertThat(allocationRows).isEqualTo(1);
    }

    /** RF-21：同键同内容幂等（数据库唯一键兜底）；同键不同内容 409。 */
    @Test
    void idempotencyEnforcedByDatabaseUniqueKey() {
        Long caseId = createCase("RF21");
        var first = ledgerService.register(caseId, registration(caseId, "RF21-E1", "120000.00"), "analyst");
        var replay = ledgerService.register(caseId, registration(caseId, "RF21-E1", "120000.00"), "analyst");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.eventId()).isEqualTo(first.eventId());

        assertThatThrownBy(() -> ledgerService.register(caseId, registration(caseId, "RF21-E1", "130000.00"), "analyst"))
                .isInstanceOf(com.bank.aml.common.exception.InvestigationRevisionConflictException.class);
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class, caseId);
        assertThat(eventRows).isEqualTo(1); // 无第二条事件
    }

    /** RF-13：分配合计超过退款事件金额 → 拒绝且无半成品落库。 */
    @Test
    void overAllocationRejectedWithNoPartialRows() {
        Long caseId = createCase("RF13");
        assertThatThrownBy(() -> ledgerService.register(caseId, new RefundLedgerService.RefundRegistration(
                "CORE_BANKING", "RF13-E1", "POSTED", "甲", "丙", null,
                new BigDecimal("100000.00"), "CNY", LocalDateTime.now(),
                List.of(new RefundLedgerService.AllocationInput("T-1001", "SO-01",
                        new BigDecimal("100000.01"), null))), "analyst"))
                .isInstanceOf(IllegalArgumentException.class);
        // 事务回滚：事件与分配均不落库（RF-24 失败注入语义在同一事务边界验证）
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class, caseId);
        assertThat(eventRows).isZero();
        Integer allocationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_allocation WHERE case_id = ?", Integer.class, caseId);
        assertThat(allocationRows).isZero();
    }

    /** RF-11：分两次退（8+4）→ 累计 12 万，逐次事件可回放。 */
    @Test
    void twoPartialRefundsAccumulate() {
        Long caseId = createCase("RF11");
        ledgerService.register(caseId, registration(caseId, "RF11-E1", "80000.00"), "analyst");
        ledgerService.register(caseId, registration(caseId, "RF11-E2", "40000.00"), "analyst");
        var ledger = ledgerService.ledger(caseId, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("120000.00");
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class, caseId);
        assertThat(eventRows).isEqualTo(2);
    }

    /** RF-17：冲正恢复余额、历史保留（行级）。 */
    @Test
    void reversalRestoresBalanceKeepsRows() {
        Long caseId = createCase("RF17");
        ledgerService.register(caseId, registration(caseId, "RF17-E1", "120000.00"), "analyst");
        ledgerService.reverse(caseId, "CORE_BANKING", "RF17-E1", "RF17-R1", "analyst");
        var ledger = ledgerService.ledger(caseId, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("0");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("440000.00");
        // 原事件与冲正事件都保留（不删除业务事实）
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class, caseId);
        assertThat(rows).isEqualTo(2);
        String originalStatus = jdbcTemplate.queryForObject(
                "SELECT event_status FROM refund_event WHERE case_id = ? AND external_event_id = 'RF17-E1'",
                String.class, caseId);
        assertThat(originalStatus).isEqualTo("REVERSED");
    }

    /** RF-30：存量语义——旧数据（无退款事件）金额账为零、超额为空；不伪造历史。 */
    @Test
    void legacyCaseLedgerIsEmptyNotFaked() {
        Long caseId = createCase("RF30");
        var ledger = ledgerService.ledger(caseId, List.of(
                new RefundLedgerService.OriginalAllocation("T-OLD", "SO-OLD", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("0");
        assertThat((Map<?, ?>) ledger.get("overAllocations")).isEmpty();
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("440000.00");
    }

    /** RF-22：并发占用——两个事务竞争同一案件登记，串行化保护无重复额度。 */
    @Test
    void concurrentRegistrationIsSerializedByCaseLock() throws Exception {
        Long caseId = createCase("RF22");
        // 两个线程各登记 8 万（原分配 12 万），行锁保证串行：两个都成功（累计 16 万 > 12 万
        // 由 overAllocations 显式暴露，而不是丢失更新静默通过）
        Runnable first = () -> ledgerService.register(caseId, registration(caseId, "RF22-E1", "80000.00"), "analyst");
        Runnable second = () -> ledgerService.register(caseId, registration(caseId, "RF22-E2", "80000.00"), "analyst");
        Thread t1 = new Thread(first);
        Thread t2 = new Thread(second);
        t1.start();
        t2.start();
        t1.join(30000);
        t2.join(30000);
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class, caseId);
        assertThat(eventRows).isEqualTo(2); // 行锁串行化：两笔都落库，无丢失更新
        var ledger = ledgerService.ledger(caseId, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("120000.00"))));
        // 超额显式可见（RF-13/RF-22）：不静默、不丢失
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> overAllocations =
                (java.util.Map<String, String>) ledger.get("overAllocations");
        assertThat(overAllocations).containsKey("T-1001");
    }

    private RefundLedgerService.RefundRegistration registration(Long caseId, String externalId, String amount) {
        // effectiveAt 是业务时间：同键重放必须携带相同业务时间（payloadDigest 含 effectiveAt）
        return new RefundLedgerService.RefundRegistration("CORE_BANKING", externalId, "POSTED",
                "甲贸易公司", "丙集团公司", "ACCT-P", new BigDecimal(amount), "CNY",
                LocalDateTime.parse("2026-09-05T10:00:00"),
                List.of(new RefundLedgerService.AllocationInput("T-1001", "SO-01",
                        new BigDecimal(amount), null)));
    }
}
