package com.bank.aml.explanation;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.domain.TransactionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GroupPaymentClaimValidator} 的直接单测。
 *
 * <p>
 * 为什么要单独测一遍：这些规则原先长在 {@code ExplanationWorkspaceService} 里，
 * 走服务级用例（{@code ExplanationWorkspaceServiceTest} 里那 5 个 TP-06/09/10/11/12）
 * 只能覆盖到前面的分支——**只要 C3 不是 SUPPORTED 或 authority 缺失就抛了**， 于是
 * {@code authority.facts}、付款时点有效性（RF-18/RF-19）、 服务器来源日期这几段**一直没有被任何测试走到**。搬出来之后正好逐段补上。
 *
 * <p>
 * 断言的是"这一层自己会拒绝什么"，不经过 Controller/Service 的其它环节； 服务级用例仍然保留，它证明的是接进链路之后行为不变。
 */
class GroupPaymentClaimValidatorTest {

    private static final Long CASE_ID = 7L;

    private static final Long UNIT_ID = 100L;

    private final CustomerDataPort customerData = mock(CustomerDataPort.class);

    private final ExplanationClaimService claimService = mock(ExplanationClaimService.class);

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final CaseEntity caseEntity = mock(CaseEntity.class);

    private final AlertExplanationUnit unit = mock(AlertExplanationUnit.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final GroupPaymentClaimValidator validator = new GroupPaymentClaimValidator(claimService,
            new PaymentAuthorityFactService(), new ExplanationServerFacts(customerData), clock);

    @BeforeEach
    void setUp() {
        when(caseEntity.getId()).thenReturn(CASE_ID);
        when(caseEntity.getCustomerId()).thenReturn("C001");
        when(unit.getId()).thenReturn(UNIT_ID);
        when(claimService.viewAll(any(), any())).thenReturn(List.of());
        // 服务器冻结的交易来源：T-1001/T-1002 是权威来源，其它自编 ID 会被拒绝
        when(customerData.transactionsOf("C001")).thenReturn(List.of(transaction("T-1001", "320000.00", "2026-09-01"),
                transaction("T-1002", "120000.00", "2026-09-02")));
    }

    // ================ 正例 ================

    @Test
    void acceptsAFullySupportedDraft() {
        assertThatCode(() -> validate(validDraft())).doesNotThrowAnyException();
    }

    @Test
    void acceptsWhenGrantIsStillEffectiveAtPaymentDate() {
        ObjectNode draft = validDraft();
        // 授权 2026-01-01 生效、无失效日 → 两笔付款发生时均有效
        authorityFact(draft, "GRANTED", "2026-01-01", "");
        assertThatCode(() -> validate(draft)).doesNotThrowAnyException();
    }

    // ================ claims 段 ================

    @Test
    void rejectsMissingClaimsSection() {
        ObjectNode draft = validDraft();
        draft.remove("claims");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("需声明 C1~C6 事实");
    }

    @Test
    void rejectsC1NotSupported() {
        ObjectNode draft = validDraft();
        draft.withObject("/claims/C1").put("status", "UNRESOLVED");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("C1").hasMessageContaining("不能建议 EXPLAINED");
    }

    @Test
    void rejectsStatusOutsideAllowedSet() {
        ObjectNode draft = validDraft();
        draft.withObject("/claims/C1").put("status", "WHATEVER");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("不在允许范围");
    }

    @Test
    void rejectsJudgementShorterThanTenCharacters() {
        ObjectNode draft = validDraft();
        draft.withObject("/claims/C1").put("judgement", "太短");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("至少 10 个字符");
    }

    @Test
    void rejectsDraftStatusDivergingFromPersistedClaim() {
        // 草稿说 C1 SUPPORTED，但持久化 Claim 是 UNRESOLVED → 必须基于最新 Claim 修订
        when(claimService.viewAll(CASE_ID, UNIT_ID)).thenReturn(List.of(new ExplanationViews.ClaimView(1L, "C1",
                "UNRESOLVED", "CRITICAL", "j", null, null, null, 1, "analyst", List.of(), 0)));
        assertThatThrownBy(() -> validate(validDraft())).hasMessageContaining("不一致");
    }

    @Test
    void rejectsContradictedC5() {
        ObjectNode draft = validDraft();
        draft.withObject("/claims").putObject("C5").put("status", "CONTRADICTED");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("C5").hasMessageContaining("CONTRADICTED");
    }

    @Test
    void allowsOmittedC5AndC6() {
        // C5/C6 可整体省略（视为未评估），不阻断
        assertThatCode(() -> validate(validDraft())).doesNotThrowAnyException();
    }

    // ================ authority 段 ================

    @Test
    void rejectsMissingAuthority() {
        ObjectNode draft = validDraft();
        draft.remove("authority");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("代付授权");
    }

    @Test
    void rejectsAuthorityRefTooShort() {
        ObjectNode draft = validDraft();
        draft.withObject("/authority").put("authorityRef", "AU");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("可定位的授权编号");
    }

    @Test
    void rejectsNonDecimalLimit() {
        ObjectNode draft = validDraft();
        draft.withObject("/authority").put("limitAmount", "四十万");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("定点数字符串");
    }

    @Test
    void rejectsEmptyCoveredTransactions() {
        ObjectNode draft = validDraft();
        draft.withObject("/authority").putArray("coveredTransactionIds");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("覆盖交易集合");
    }

    @Test
    void rejectsCoveredTransactionOutsideServerSource() {
        ObjectNode draft = validDraft();
        ArrayNode covered = draft.withObject("/authority").putArray("coveredTransactionIds");
        covered.add("T-1001").add("T-9999"); // 服务器冻结来源里没有 T-9999
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("T-9999").hasMessageContaining("冻结的交易来源集合");
    }

    @Test
    void rejectsDuplicateCoveredTransaction() {
        ObjectNode draft = validDraft();
        ArrayNode covered = draft.withObject("/authority").putArray("coveredTransactionIds");
        covered.add("T-1001").add("T-1001");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("重复交易");
    }

    @Test
    void rejectsRequiredLegNotCovered() {
        // 只覆盖 T-1001，但 scope 里 T-1002 也是待解释的资金腿
        ObjectNode draft = validDraft();
        ArrayNode covered = draft.withObject("/authority").putArray("coveredTransactionIds");
        covered.add("T-1001");
        authorityFact(draft, "GRANTED", "2026-01-01", "");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("T-1002").hasMessageContaining("未被授权覆盖");
    }

    @Test
    void rejectsLimitBelowCoveredTotal() {
        // 覆盖 44 万，额度 40 万 → 超出部分不得整笔解释成立
        ObjectNode draft = validDraft();
        draft.withObject("/authority").put("limitAmount", "400000.00");
        authorityFact(draft, "GRANTED", "2026-01-01", "");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("低于已覆盖交易合计").hasMessageContaining("不得整笔解释成立");
    }

    // ================ 付款时点有效性（RF-18/RF-19）——服务级用例到不了这里 ================

    @Test
    void rejectsMissingAuthorityFacts() {
        ObjectNode draft = validDraft();
        draft.withObject("/authority").remove("facts");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("有效期事实").hasMessageContaining("不能跳过付款时点校验");
    }

    @Test
    void rejectsEmptyAuthorityFacts() {
        ObjectNode draft = validDraft();
        draft.withObject("/authority").putArray("facts");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("有效期事实");
    }

    @Test
    void rejectsFactReferencingAnotherAuthority() {
        ObjectNode draft = validDraft();
        authorityFact(draft, "GRANTED", "2026-01-01", "");
        ((ObjectNode) draft.withArray("/authority/facts").get(0)).put("authorityRef", "AU-OTHER");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("AU-OTHER").hasMessageContaining("不一致");
    }

    @Test
    void rejectsRevokedBeforePayment() {
        // 撤销生效日 2026-08-01 早于付款日 2026-09-01 → 付款时点已无效
        ObjectNode draft = validDraft();
        ArrayNode facts = draft.withObject("/authority").putArray("facts");
        fact(facts, "GRANTED", "2026-01-01", "");
        fact(facts, "REVOKED", "2026-08-01", "");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("有效性");
    }

    @Test
    void rejectsNonIsoDateInFacts() {
        ObjectNode draft = validDraft();
        authorityFact(draft, "GRANTED", "2026/01/01", "");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("ISO 格式");
    }

    @Test
    void rejectsTransactionWithoutServerSidePaymentDate() {
        // 服务器来源里没有这笔交易的日期 → 不得用客户端日期替代
        when(customerData.transactionsOf("C001"))
            .thenReturn(List.of(new TransactionRecord(null, new BigDecimal("320000.00"), "转入", "丙集团公司", null, "企业网银",
                    "货款结算", "CNY", "T-1001"), transaction("T-1002", "120000.00", "2026-09-02")));
        ObjectNode draft = validDraft();
        authorityFact(draft, "GRANTED", "2026-01-01", "");
        assertThatThrownBy(() -> validate(draft)).hasMessageContaining("付款发生日").hasMessageContaining("不能使用客户端日期替代");
    }

    // ================ 夹具 ================

    private void validate(ObjectNode draft) {
        validator.validate(caseEntity, unit, draft);
    }

    /** 一份能通过全部规则的代付草稿；各用例只需改动自己关心的那一处。 */
    private ObjectNode validDraft() {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode claims = draft.putObject("claims");
        claims.putObject("C1").put("status", "SUPPORTED").put("judgement", "核心流水付款账户归属丙集团公司，KYC 档案已核对");
        claims.putObject("C2").put("status", "SUPPORTED").put("judgement", "订单 SO-01/SO-02 与签收记录对应乙对甲的货款义务");
        claims.putObject("C3").put("status", "SUPPORTED").put("judgement", "授权 AU-01 覆盖 SO-01/SO-02，额度与有效期已核对");
        claims.putObject("C4").put("status", "SUPPORTED").put("judgement", "两笔收款在授权范围内履行乙的付款义务");
        ObjectNode scope = draft.putObject("scope");
        ArrayNode reviewed = scope.putArray("reviewedTransactionIds");
        reviewed.add("T-1001").add("T-1002");
        ObjectNode authority = draft.putObject("authority");
        authority.put("authorityRef", "AU-01");
        authority.put("limitAmount", "500000.00");
        ArrayNode covered = authority.putArray("coveredTransactionIds");
        covered.add("T-1001").add("T-1002");
        // 一份能通过的草稿必须带可用的事实序列——空数组同样被拒（见 rejectsEmptyAuthorityFacts）
        fact(authority.putArray("facts"), "GRANTED", "2026-01-01", "");
        return draft;
    }

    /** 给 authority.facts 追加一条事实。effectiveTo 传空串表示不声明失效日。 */
    private void authorityFact(ObjectNode draft, String factType, String effectiveFrom, String effectiveTo) {
        fact(draft.withObject("/authority").putArray("facts"), factType, effectiveFrom, effectiveTo);
    }

    private void fact(ArrayNode facts, String factType, String effectiveFrom, String effectiveTo) {
        ObjectNode node = facts.addObject();
        node.put("authorityRef", "AU-01");
        node.put("factType", factType);
        node.put("effectiveFrom", effectiveFrom);
        node.put("effectiveTo", effectiveTo);
    }

    private TransactionRecord transaction(String sourceRecordId, String amount, String date) {
        return new TransactionRecord(LocalDateTime.parse(date + "T10:15:00"), new BigDecimal(amount), "转入", "丙集团公司",
                null, "企业网银", "货款结算", "CNY", sourceRecordId);
    }

}
