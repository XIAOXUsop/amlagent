package com.bank.aml.explanation;

import com.bank.aml.investigation.InvestigationReadinessEvaluator;

/**
 * v2 解释核验就绪端口：供调查视图/运营队列按契约版本分流，避免口径分叉。
 * 实现方为 {@link ExplanationWorkspaceService}；v0/v1 案件不经过该端口。
 */
public interface ExplanationReadinessPort {

    /** v2 决策表就绪结论（映射为与 v1 相同的 Result 形状）。 */
    InvestigationReadinessEvaluator.Result readinessForCase(Long caseId);
}
