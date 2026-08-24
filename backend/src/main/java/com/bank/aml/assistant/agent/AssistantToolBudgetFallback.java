package com.bank.aml.assistant.agent;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.guard.AssistantIntent;

import java.util.stream.Collectors;

/** 工具轮次耗尽时基于同一冻结快照生成只读、无模型二次调用的安全摘要。 */
public final class AssistantToolBudgetFallback {
    private AssistantToolBudgetFallback() {}

    public static String create(CustomerAssistantSnapshot snapshot, AssistantIntent intent) {
        if (intent == AssistantIntent.BANKING_KNOWLEDGE) {
            String evidence = snapshot.evidence().stream()
                    .filter(item -> item.type() == AssistantEvidence.EvidenceType.AML_LEGAL
                            || item.type() == AssistantEvidence.EvidenceType.BANKING_PUBLIC)
                    .map(item -> "- " + item.title() + "：" + item.summary())
                    .collect(Collectors.joining("\n"));
            return "## 可核验证据摘要\n\n" + (evidence.isBlank() ? "当前数据不足。" : evidence)
                    + "\n\n> 数据局限：以上仅来自本次冻结的法规/银行金融证据，不构成自动审批或业务操作。";
        }

        var customer = snapshot.customer();
        var transaction = snapshot.transactionRisk();
        var ownership = snapshot.ownershipRisk();
        var sanction = snapshot.sanctionRisk();
        return """
                ## 当前客户只读风险摘要

                ### 已核验事实
                - 客户类型：%s；行业：%s；地区：%s；状态：%s。
                - 近180天交易笔数：%d笔；交易总额：%s；平均金额：%s。
                - 夜间交易占比：%s%%；跨境交易占比：%s%%；大额交易笔数：%d笔；交易模式严重度：%d。
                - 股权关系数：%d；受益所有人风险严重度：%d。
                - 制裁筛查是否命中：%s；命中数：%d；最高严重度：%d。

                ### 分析提示
                上述聚合指标可作为人工尽调线索，但不能单独替代交易背景、资金用途、交易对手和制裁名单的人工核验。

                > 数据局限：本回答基于本次冻结快照，只读且不会修改客户、工单、账户或审核状态。
                """.formatted(safe(customer.customerType()), safe(customer.industry()), safe(customer.region()),
                safe(customer.status()), transaction.transactionCount(), transaction.totalAmount(),
                transaction.averageAmount(), transaction.nightRatio(), transaction.crossBorderRatio(),
                transaction.largeTransactionCount(), transaction.patternSeverity(), ownership.relationCount(),
                ownership.uboRiskSeverity(), sanction.hit() ? "是" : "否", sanction.hitCount(),
                sanction.maxSeverity());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }
}
