package com.bank.aml.refund;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.testinfra.TestSqlIdentifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G4 全链验收（RF-06/RF-11/RF-13/RF-22/RF-24/RF-30）： 退款金额账在真实 MySQL
 * 上的行级验证——并发占用、超额拒绝、失败回滚无半成品、 存量语义（旧数据不被伪造）。 运行：./mvnw -Pintegration-test test
 * -Dtest=RefundLedgerIntegrationTest
 */
@Tag("integration")
@SpringBootTest
@SuppressWarnings("deprecation") // 集成测试仍需验证旧版金额账重载在真实数据库上的兼容行为。
class RefundLedgerIntegrationTest {

    private static final String SCHEMA = "aml_refund_ledger_test";

    /** 用真实演示客户：权威交易是从客户数据端口按 customerId 取的，凭空造的客户必然取不到 */
    private static final String CUSTOMER_ID = "C001";

    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");

    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");

    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");

    private static final String JDBC_OPTIONS = "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&useSSL=false&allowPublicKeyRetrieval=true";

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        String serverUrl = "jdbc:mysql://" + HOST + "/" + JDBC_OPTIONS;
        String quotedSchema = TestSqlIdentifier.mysqlSchema(SCHEMA);
        try (Connection conn = DriverManager.getConnection(serverUrl, ROOT_USER, ROOT_PASSWORD);
                Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + quotedSchema);
            st.execute("CREATE DATABASE " + quotedSchema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        catch (Exception e) {
            throw new IllegalStateException("无法创建隔离 schema " + SCHEMA, e);
        }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + HOST + "/" + SCHEMA + JDBC_OPTIONS);
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

    @Autowired
    private CustomerDataPort customerDataPort;

    private TransactionRecord authoritativeTx;

    /**
     * 案件客户的**真实**权威交易。
     *
     * <p>
     * 退款分配必须指向它：服务端只认权威来源里的原付款与金额， 调用方传进来的金额一律忽略（RF-14）。原先这些用例用的是凭空造的 `T-1001`，
     * 那条校验一上线就全数失败——不是校验错了，是夹具还是旧的。
     */
    private TransactionRecord authoritative() {
        if (authoritativeTx == null) {
            authoritativeTx = customerDataPort.transactionsOf(CUSTOMER_ID)
                .stream()
                .filter(t -> "CNY".equalsIgnoreCase(t.currency()))
                .filter(t -> t.sourceRecordId() != null && !t.sourceRecordId().isBlank())
                .max(Comparator.comparing(TransactionRecord::amount))
                .orElseThrow(() -> new IllegalStateException("演示客户 " + CUSTOMER_ID + " 没有可用的 CNY 权威交易，本用例无法构造"));
        }
        return authoritativeTx;
    }

    private String txId() {
        return authoritative().sourceRecordId();
    }

    private BigDecimal txAmount() {
        return authoritative().amount();
    }

    private Long createCase(String tag) {
        CaseEntity c = new CaseEntity();
        c.setCustomerId(CUSTOMER_ID);
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
        var result = ledgerService.register(caseId, new RefundLedgerService.RefundRegistration("CORE_BANKING",
                "RF06-E1", "POSTED", "甲贸易公司", "丙集团公司", "ACCT-P", new BigDecimal("120000.00"), "CNY",
                LocalDateTime.now(),
                List.of(new RefundLedgerService.AllocationInput(txId(), "SO-01", new BigDecimal("120000.00"), null))),
                "analyst");
        assertThat(result.idempotentReplay()).isFalse();

        var ledger = ledgerService.ledger(caseId,
                List.of(new RefundLedgerService.OriginalAllocation(txId(), "SO-01", txAmount())));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("120000.00");
        assertThat((BigDecimal) ledger.get("totalRetained"))
            .isEqualByComparingTo(txAmount().subtract(new BigDecimal("120000.00")));

        // 行级：退款事件与分配真实落库
        Integer eventRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_event WHERE case_id = ?",
                Integer.class, caseId);
        assertThat(eventRows).isEqualTo(1);
        Integer allocationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_allocation WHERE case_id = ? AND original_transaction_id = ?",
                Integer.class, caseId, txId());
        assertThat(allocationRows).isEqualTo(1);
    }

    /** 数据库本身拒绝跨案件、跨币种和非正数分配，不能只依赖应用层校验。 */
    @Test
    void databaseEnforcesRefundAllocationIdentityAndAmount() {
        Long eventCaseId = createCase("DB-INTEGRITY-EVENT");
        Long anotherCaseId = createCase("DB-INTEGRITY-OTHER");
        long eventId = ledgerService
            .register(eventCaseId, registration(eventCaseId, "DB-INTEGRITY-E1", "100.00"), "analyst")
            .eventId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refund_allocation "
                        + "(case_id, refund_event_id, original_transaction_id, original_allocation_key, "
                        + "allocated_amount, currency, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6))",
                anotherCaseId, eventId, "T-CROSS-CASE", "A-CROSS-CASE", new BigDecimal("1.00"), "CNY", "test"))
            // 断言到**具体哪条约束**：跨案件是由 (refund_event_id, case_id, currency)
            // 这个复合外键拦下的，不是靠应用层记得校验
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("fk_refund_alloc_event_identity");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refund_allocation "
                        + "(case_id, refund_event_id, original_transaction_id, original_allocation_key, "
                        + "allocated_amount, currency, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6))",
                eventCaseId, eventId, "T-CROSS-CURRENCY", "A-CROSS-CURRENCY", new BigDecimal("1.00"), "USD", "test"))
            // 币种不是 CNY 时先撞上 CHECK 约束（MySQL 报 3819，Spring 归为
            // UncategorizedSQLException 而不是 DataIntegrityViolationException——
            // 旧断言写死了后者，一旦这条路径真的被执行就会失败）
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("chk_refund_alloc_currency");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refund_allocation "
                        + "(case_id, refund_event_id, original_transaction_id, original_allocation_key, "
                        + "allocated_amount, currency, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6))",
                eventCaseId, eventId, "T-ZERO", "A-ZERO", BigDecimal.ZERO, "CNY", "test"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("chk_refund_alloc_amount_positive");

        long otherEventId = ledgerService
            .register(anotherCaseId, registration(anotherCaseId, "DB-INTEGRITY-E2", "20.00"), "analyst")
            .eventId();
        // 冲正必须指向同一案件内的另一笔事件：跨案件与指向不存在的事件都由数据库拦下
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE refund_event SET reversed_event_id = ? WHERE id = ?",
                otherEventId, eventId))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE refund_event SET reversed_event_id = ? WHERE id = ?",
                Long.MAX_VALUE, eventId))
            .isInstanceOf(DataAccessException.class);
    }

    /** RF-21：同键同内容幂等（数据库唯一键兜底）；同键不同内容 409。 */
    @Test
    void idempotencyEnforcedByDatabaseUniqueKey() {
        Long caseId = createCase("RF21");
        var first = ledgerService.register(caseId, registration(caseId, "RF21-E1", "120000.00"), "analyst");
        var replay = ledgerService.register(caseId, registration(caseId, "RF21-E1", "120000.00"), "analyst");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.eventId()).isEqualTo(first.eventId());

        assertThatThrownBy(
                () -> ledgerService.register(caseId, registration(caseId, "RF21-E1", "130000.00"), "analyst"))
            .isInstanceOf(InvestigationRevisionConflictException.class);
        Integer eventRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_event WHERE case_id = ?",
                Integer.class, caseId);
        assertThat(eventRows).isEqualTo(1); // 无第二条事件
    }

    /** RF-13：分配合计超过退款事件金额 → 拒绝且无半成品落库。 */
    @Test
    void overAllocationRejectedWithNoPartialRows() {
        Long caseId = createCase("RF13");
        assertThatThrownBy(() -> ledgerService.register(caseId,
                new RefundLedgerService.RefundRegistration(
                        "CORE_BANKING", "RF13-E1", "POSTED", "甲", "丙", null, new BigDecimal("100000.00"), "CNY",
                        LocalDateTime.now(), List.of(new RefundLedgerService.AllocationInput(txId(), "SO-01",
                                new BigDecimal("100000.01"), null))),
                "analyst"))
            .isInstanceOf(IllegalArgumentException.class);
        // 事务回滚：事件与分配均不落库（RF-24 失败注入语义在同一事务边界验证）
        Integer eventRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_event WHERE case_id = ?",
                Integer.class, caseId);
        assertThat(eventRows).isZero();
        Integer allocationRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_allocation WHERE case_id = ?",
                Integer.class, caseId);
        assertThat(allocationRows).isZero();
    }

    /** RF-11：分两次退（8+4）→ 累计 12 万，逐次事件可回放。 */
    @Test
    void twoPartialRefundsAccumulate() {
        Long caseId = createCase("RF11");
        ledgerService.register(caseId, registration(caseId, "RF11-E1", "80000.00"), "analyst");
        ledgerService.register(caseId, registration(caseId, "RF11-E2", "40000.00"), "analyst");
        var ledger = ledgerService.ledger(caseId,
                List.of(new RefundLedgerService.OriginalAllocation(txId(), "SO-01", txAmount())));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("120000.00");
        Integer eventRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_event WHERE case_id = ?",
                Integer.class, caseId);
        assertThat(eventRows).isEqualTo(2);
    }

    /** RF-17：冲正恢复余额、历史保留（行级）。 */
    @Test
    void reversalRestoresBalanceKeepsRows() {
        Long caseId = createCase("RF17");
        ledgerService.register(caseId, registration(caseId, "RF17-E1", "120000.00"), "analyst");
        ledgerService.reverse(caseId, "CORE_BANKING", "RF17-E1", "RF17-R1", "analyst");
        var ledger = ledgerService.ledger(caseId,
                List.of(new RefundLedgerService.OriginalAllocation(txId(), "SO-01", txAmount())));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("0");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo(txAmount());
        // 原事件与冲正事件都保留（不删除业务事实）
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_event WHERE case_id = ?", Integer.class,
                caseId);
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
        var ledger = ledgerService.ledger(caseId,
                List.of(new RefundLedgerService.OriginalAllocation(txId(), "SO-01", txAmount())));

        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("0");
        assertThat((Map<?, ?>) ledger.get("overAllocations")).isEmpty();
        // 原付款金额只认权威来源，不按调用方传进来的数字记账
        assertThat((BigDecimal) ledger.get("totalOriginal")).isEqualByComparingTo(txAmount());
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo(txAmount());
    }

    /**
     * RF-30 的另一半：调用方声称的原付款如果**不在**权威来源里，金额账不能按它记账。
     *
     * <p>
     * 旧版会把客户端给的原付款当成事实——一笔凭空写下的「44 万」就能进账。 与上一条的区别正在这里：上一条是"真的原付款、只是还没退过"，
     * 这一条是"这个原付款根本不存在"。
     */
    @Test
    void nonAuthoritativeOriginalTransactionIsNotCounted() {
        Long caseId = createCase("RF30X");

        var ledger = ledgerService.ledger(caseId, List
            .of(new RefundLedgerService.OriginalAllocation("T-NOT-IN-SOURCE", "SO-X", new BigDecimal("440000.00"))));

        assertThat((BigDecimal) ledger.get("totalOriginal")).isEqualByComparingTo("0");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("0");
    }

    /** RF-22：并发占用——两个事务竞争同一案件登记，串行化保护无重复额度。 */
    @Test
    void concurrentRegistrationIsSerializedByCaseLock() throws Exception {
        Long caseId = createCase("RF22");
        // 两个线程各退权威金额的三分之一：合计仍在原付款之内，行锁保证串行、两笔都落库。
        // （旧版这里各退 8 万、合计超过 12 万，期望"两笔都成功、超额出现在账上"——
        // RF-14 上线后超额在**登记**时就抛异常，那个期望已经不成立了。）
        BigDecimal each = txAmount().divide(new BigDecimal("3"), 2, java.math.RoundingMode.DOWN);
        Runnable first = () -> ledgerService.register(caseId, registration(caseId, "RF22-E1", each.toPlainString()),
                "analyst");
        Runnable second = () -> ledgerService.register(caseId, registration(caseId, "RF22-E2", each.toPlainString()),
                "analyst");
        Thread t1 = new Thread(first);
        Thread t2 = new Thread(second);
        t1.start();
        t2.start();
        t1.join(30000);
        t2.join(30000);
        Integer eventRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_event WHERE case_id = ?",
                Integer.class, caseId);
        assertThat(eventRows).isEqualTo(2); // 行锁串行化：两笔都落库，无丢失更新
        var ledger = ledgerService.ledger(caseId,
                List.of(new RefundLedgerService.OriginalAllocation(txId(), "SO-01", txAmount())));
        // 两笔都真实入账，且既没有重复计入（无丢失更新）也没有超额
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo(each.add(each));
        assertThat((Map<?, ?>) ledger.get("overAllocations")).isEmpty();

        // 超额方向由 RF-14 在登记时直接拒绝——不是在账上留个记号等人看
        assertThatThrownBy(() -> ledgerService.register(caseId,
                registration(caseId, "RF22-E3", txAmount().toPlainString()), "analyst"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private RefundLedgerService.RefundRegistration registration(Long caseId, String externalId, String amount) {
        // effectiveAt 是业务时间：同键重放必须携带相同业务时间（payloadDigest 含 effectiveAt）
        return new RefundLedgerService.RefundRegistration("CORE_BANKING", externalId, "POSTED", "甲贸易公司", "丙集团公司",
                "ACCT-P", new BigDecimal(amount), "CNY", LocalDateTime.parse("2026-09-05T10:00:00"),
                List.of(new RefundLedgerService.AllocationInput(txId(), "SO-01", new BigDecimal(amount), null)));
    }

}
