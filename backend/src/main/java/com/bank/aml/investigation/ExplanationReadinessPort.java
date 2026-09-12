package com.bank.aml.investigation;

/**
 * v2 解释核验就绪端口：供调查视图/运营队列按契约版本分流，避免口径分叉。 v0/v1 案件不经过该端口；实现由解释核验模块提供。
 */
public interface ExplanationReadinessPort {

    /** v2 决策表就绪结论（映射为与 v1 相同的 Result 形状）。 */
    InvestigationReadinessEvaluator.Result readinessForCase(Long caseId);

}
