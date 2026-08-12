package com.bank.aml.common.enums;

/**
 * Agent 工作流阶段（每次切换均持久化并推送 SSE）。
 * 执行顺序：PLANNING → COLLECTING → REASONING → GUARDRAIL → REPORTING → DONE
 */
public enum WorkflowStage {
    /** 任务规划：解析预警工单、拆解子任务 */
    PLANNING,
    /** 数据采集：并行调用多源工具 */
    COLLECTING,
    /** 风险推理：综合研判风险特征 */
    REASONING,
    /** 规则护栏：制裁名单 / 评级一致性校验 */
    GUARDRAIL,
    /** 报告生成：结构化尽调报告 */
    REPORTING,
    /** 完成：归档 / 转人工 */
    DONE
}
