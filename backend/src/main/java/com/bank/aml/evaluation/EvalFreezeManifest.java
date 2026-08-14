package com.bank.aml.evaluation;

import java.time.Instant;

/**
 * 隐藏 TEST 冻结清单：完整记录代码、数据集、Prompt、规则、模型、温度与法规索引版本，
 * 使任一正式评测结果都能唯一关联到运行基线。
 */
public record EvalFreezeManifest(
        String freezeId,
        String commitSha,
        String datasetId,
        String datasetVersion,
        String datasetHash,
        String promptVersion,
        String ruleSetHash,
        String legalIndexVersion,
        String provider,
        String model,
        Double temperature,
        Instant createdAt
) {
}
