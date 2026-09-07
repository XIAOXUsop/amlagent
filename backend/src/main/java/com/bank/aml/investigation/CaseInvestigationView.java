package com.bank.aml.investigation;

import java.util.List;

/**
 * 案件调查视图。
 *
 * @param readyForFinalReview 两种结案路径（确认可疑 / 排除预警）至少一条不被阻断
 * @param generalBlockers     与具体决定无关的调查完成度阻断（供分析员待办与运营展示）
 */
public record CaseInvestigationView(
        int contractVersion,
        List<AlertView> alerts,
        List<InvestigationHypothesisView> hypotheses,
        List<AlertCoverageView> coverage,
        boolean readyForFinalReview,
        List<String> generalBlockers,
        List<String> confirmSuspiciousBlockers,
        List<String> excludeFalsePositiveBlockers
) { }
