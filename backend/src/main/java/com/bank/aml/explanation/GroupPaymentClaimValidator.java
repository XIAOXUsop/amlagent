package com.bank.aml.explanation;

import com.bank.aml.datasource.entity.CaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bank.aml.explanation.ExplanationJson.stringList;
import static com.bank.aml.explanation.ExplanationJson.text;

/**
 * 集团代付配方（claims / authority）的事实约束校验。
 *
 * <p>
 * 从 {@link ExplanationWorkspaceService} 里整块搬出来的，**逻辑逐字未改**—— 这是 v3
 * 计划里「控制代码复杂度」那一步的第一次提取，按方案要求 「一次只提取一个职责，每次保持现有 API 和测试不变」。
 *
 * <p>
 * 挑它先动的原因是它的耦合面最干净：整块只被 {@code validateDraftContent} 调用一次，
 * 内部只用一个私有工具方法（{@code parseDateOrNull}，也一并搬来）， 依赖面是自成一组的四个协作者。搬走之后
 * {@link ExplanationWorkspaceService} 不再 "自己实现代付事实规则"，只剩调用。
 *
 * <p>
 * 协作者由外层组合而不是作为自己的构造参数注入——与同包的 {@link ExplanationServerFacts} 是同一种写法，这样不用去动那个 25 参数的构造器
 * （它有 6 处测试构造点）。
 *
 * <p>
 * 这里**只做搬运**：签名、异常文案、判定顺序、{@code A6-02}/{@code TP-xx} 之类的
 * 规则编号全部保持原样。任何语义改动都应当有独立的理由与测试，不夹在搬运里。
 */
final class GroupPaymentClaimValidator {

    private final ExplanationClaimService claimService;

    private final PaymentAuthorityFactService paymentAuthorityFactService;

    private final ExplanationServerFacts serverFacts;

    private final Clock clock;

    GroupPaymentClaimValidator(ExplanationClaimService claimService,
            PaymentAuthorityFactService paymentAuthorityFactService, ExplanationServerFacts serverFacts, Clock clock) {
        this.claimService = claimService;
        this.paymentAuthorityFactService = paymentAuthorityFactService;
        this.serverFacts = serverFacts;
        this.clock = clock;
    }

    /**
     * 代付配方的事实约束（v3 计划 §6/§8；TP-06/TP-09）： 草稿中 claims 节声明 C1~C6 状态；EXPLAINED 要求 C1~C4 全部
     * SUPPORTED。 C5/C6 允许 UNRESOLVED（保留未知）但不允许 CONTRADICTED（矛盾必须先处理）。
     */
    void validate(CaseEntity caseEntity, AlertExplanationUnit unit, JsonNode draft) {
        JsonNode claims = draft.get("claims");
        if (claims == null || !claims.isObject()) {
            throw new IllegalArgumentException("集团代付配方需声明 C1~C6 事实（claims）");
        }
        Map<String, String> persistedClaimStatuses = new LinkedHashMap<>();
        for (ExplanationViews.ClaimView persisted : claimService.viewAll(caseEntity.getId(), unit.getId())) {
            persistedClaimStatuses.put(persisted.claimCode(), persisted.status());
        }
        for (String code : new String[] { "C1", "C2", "C3", "C4" }) {
            JsonNode claim = claims.get(code);
            if (claim == null || !claim.isObject()) {
                throw new IllegalArgumentException("集团代付配方必须回答事实 " + code + "（C1~C4 不可整体跳过）");
            }
            String status = text(claim, "status", "UNASSESSED");
            if (!Set.of("SUPPORTED", "CONTRADICTED", "UNRESOLVED", "NOT_APPLICABLE", "UNASSESSED").contains(status)) {
                throw new IllegalArgumentException("事实 " + code + " 状态不在允许范围：" + status);
            }
            String persistedStatus = persistedClaimStatuses.get(code);
            if (persistedStatus != null && !persistedStatus.equals(status)) {
                throw new IllegalArgumentException("事实 " + code + " 的草稿状态 " + status + " 与当前持久化 Claim 状态 "
                        + persistedStatus + " 不一致；必须基于最新 Claim 修订草稿后再提交");
            }
            if (!"SUPPORTED".equals(status)) {
                throw new IllegalArgumentException("事实 " + code + " 状态为 " + status + "，不能建议 EXPLAINED；请先完成该事实的定向核验，"
                        + "或提交 UNRESOLVED/SUSPICIOUS 保留判断");
            }
            String judgement = text(claim, "judgement");
            if (judgement.length() < 10) {
                throw new IllegalArgumentException("事实 " + code + " 的判断需至少 10 个字符" + "（为什么证据支持该事实）");
            }
        }
        for (String code : new String[] { "C5", "C6" }) {
            JsonNode claim = claims.get(code);
            if (claim == null || !claim.isObject()) {
                continue; // C5/C6 可省略（省略视为未评估，不阻断 EXPLAINED，由 Q5/Q6 承担）
            }
            String status = text(claim, "status", "UNASSESSED");
            String persistedStatus = persistedClaimStatuses.get(code);
            if (persistedStatus != null && !persistedStatus.equals(status)) {
                throw new IllegalArgumentException(
                        "事实 " + code + " 的草稿状态 " + status + " 与当前持久化 Claim 状态 " + persistedStatus + " 不一致");
            }
            if ("CONTRADICTED".equals(status)) {
                throw new IllegalArgumentException(
                        "事实 " + code + " 存在已评估矛盾（CONTRADICTED），" + "不能建议 EXPLAINED；需先处理矛盾或改判 SUSPICIOUS");
            }
        }
        // A6-02/RC-05：授权必填——C3 SUPPORTED 不能凭空成立；authority 结构、
        // 可定位授权编号、额度与覆盖集合缺一即拒，不能整体省略。
        JsonNode authority = draft.get("authority");
        if (authority == null || !authority.isObject()) {
            throw new IllegalArgumentException(
                    "集团代付配方必须声明代付授权（authority）：" + "可定位授权编号、额度与覆盖交易集合；C3 的 SUPPORTED 声明不能替代授权记录（A6-02）");
        }
        String authorityRef = text(authority, "authorityRef");
        if (authorityRef.length() < 3) {
            throw new IllegalArgumentException("代付授权需提供可定位的授权编号（authorityRef，" + "至少 3 个字符）；授权身份缺失时 C3 不能成立");
        }
        String limitText = text(authority, "limitAmount");
        if (limitText.isBlank()) {
            throw new IllegalArgumentException("代付授权需声明额度（limitAmount）；无额度的授权" + "不能确定覆盖边界（A6-02）");
        }
        BigDecimal limit;
        try {
            limit = new BigDecimal(limitText.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("授权额度需为定点数字符串：" + limitText);
        }
        // 服务端派生需要代付解释的资金腿全集（不从客户端 coveredTransactionIds 反向定义）：
        // 集合 = 本次提交 scope.reviewedTransactionIds（提交前已通过服务器来源对账）。
        JsonNode scopeNode = draft.get("scope");
        List<String> requiredLegs = scopeNode == null ? List.of() : stringList(scopeNode, "reviewedTransactionIds");
        JsonNode coveredTxs = authority.get("coveredTransactionIds");
        if (coveredTxs == null || !coveredTxs.isArray() || coveredTxs.size() == 0) {
            throw new IllegalArgumentException(
                    "代付授权需声明覆盖交易集合（coveredTransactionIds）；" + "空覆盖不能支撑 C4 的 SUPPORTED（A6-02）");
        }
        Map<String, BigDecimal> sourceAmounts = serverFacts.amountsOf(caseEntity);
        Set<String> coveredSet = new LinkedHashSet<>();
        BigDecimal covered = BigDecimal.ZERO;
        for (JsonNode tx : coveredTxs) {
            String txId = tx.asText();
            BigDecimal amount = sourceAmounts.get(txId);
            if (amount == null) {
                throw new IllegalArgumentException("授权覆盖的交易 " + txId + " 不属于服务器冻结的交易来源集合");
            }
            if (!coveredSet.add(txId)) {
                throw new IllegalArgumentException("授权覆盖集合存在重复交易：" + txId + "（去重计量，TP-31）");
            }
            covered = covered.add(amount);
        }
        // 逐笔比对：全部需要解释的代付资金腿必须被授权覆盖；未覆盖的具体交易保留缺口
        // （TP-11：第二笔不得继承第一笔结论；TP-10：不得整笔解释成立）。
        List<String> uncovered = requiredLegs.stream().filter(tx -> !coveredSet.contains(tx)).toList();
        if (!uncovered.isEmpty()) {
            throw new IllegalArgumentException("以下待解释的代付资金腿未被授权覆盖：" + String.join("、", uncovered)
                    + "；未覆盖金额不能隐去（A6-02/TP-11），" + "需补充授权或把缺口交易移出命中范围（后者需来源更正）");
        }
        // 授权额度缺口：授权额度 < 覆盖交易合计 → 超出部分保留缺口，不得整笔解释成立（TP-10）。
        BigDecimal gap = limit.subtract(covered);
        if (gap.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "代付授权额度 " + limit.toPlainString() + " 低于已覆盖交易合计 " + covered.toPlainString() + "；超出部分 "
                            + gap.negate().toPlainString() + " 保留缺口，不得整笔解释成立" + "（TP-10）；请提交 UNRESOLVED 并说明缺口处理安排");
        }
        // G1-3/RF-18/RF-19：双时间规则——authority.facts 声明授权/撤销/追认事实序列，
        // 服务端按"付款时点"评估有效性；付款后追认不能替代付款前授权（保持 UNRESOLVED）。
        JsonNode factsNode = authority.get("facts");
        if (factsNode == null || !factsNode.isArray() || factsNode.isEmpty()) {
            throw new IllegalArgumentException("代付授权必须提供可核验的有效期事实（authority.facts）；" + "省略或空数组不能跳过付款时点校验");
        }
        List<PaymentAuthorityFactService.AuthorityFact> facts = new ArrayList<>();
        for (JsonNode factNode : factsNode) {
            String factAuthorityRef = text(factNode, "authorityRef", authorityRef);
            if (!authorityRef.equals(factAuthorityRef)) {
                throw new IllegalArgumentException("授权事实引用 " + factAuthorityRef + " 与当前授权 " + authorityRef + " 不一致");
            }
            facts.add(new PaymentAuthorityFactService.AuthorityFact(factAuthorityRef,
                    parseDateOrNull(text(factNode, "effectiveFrom")), parseDateOrNull(text(factNode, "effectiveTo")),
                    null, LocalDateTime.now(clock), text(factNode, "factType")));
        }
        Map<String, LocalDate> transactionDates = serverFacts.datesOf(caseEntity);
        for (String transactionId : requiredLegs) {
            LocalDate paymentDate = transactionDates.get(transactionId);
            if (paymentDate == null) {
                throw new IllegalArgumentException("服务器权威来源无法取得交易 " + transactionId + " 的付款发生日；不能使用客户端日期替代，授权有效性保持待核验");
            }
            PaymentAuthorityFactService.AuthorityValidity validity = paymentAuthorityFactService
                .evaluateAtPayment(facts, paymentDate);
            if ("UNKNOWN".equals(validity.validAtPayment()) || "INVALID".equals(validity.validAtPayment())) {
                throw new IllegalArgumentException(
                        "交易 " + transactionId + " 的代付授权在付款时点有效性为 " + validity.validAtPayment() + "："
                                + validity.explanation() + "；C3 不能 SUPPORTED，请提交 UNRESOLVED 并完成定向核验（RF-18/RF-19）");
            }
        }
    }

    private static LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期需为 ISO 格式（yyyy-MM-dd）：" + value, e);
        }
    }

}
