package com.bank.aml.evaluation;

import com.bank.aml.agent.AgentReportVocabulary;

import java.util.Set;

/** @deprecated 评测与生产现已共享 {@link AgentReportVocabulary}；保留别名兼容既有调用。 */
@Deprecated(forRemoval = false)
public final class AgentEvalVocabulary {

    public static final Set<String> FINDING_CODES = AgentReportVocabulary.FINDING_CODES;
    public static final Set<String> ACTION_CODES = AgentReportVocabulary.ACTION_CODES;

    private AgentEvalVocabulary() {
    }
}
