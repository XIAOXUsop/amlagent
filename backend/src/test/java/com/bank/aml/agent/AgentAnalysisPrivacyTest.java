package com.bank.aml.agent;

import com.bank.aml.evaluation.AgentEvalVocabulary;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnalysisPrivacyTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void modelOutputContractContainsNoTrustedIdentityFields() throws Exception {
        AgentAnalysis analysis = new AgentAnalysis("低风险", "tx", "corp", List.of(), List.of("LEGAL-1"),
                List.of("normal"), "conclusion", List.of("LEGAL-1"), false, List.of("NORMAL_TRANSACTION_PATTERN"),
                List.of("MAINTAIN_STANDARD_MONITORING"));

        String json = objectMapper.writeValueAsString(analysis);

        assertThat(json).doesNotContain("customerId")
            .doesNotContain("customerName")
            .doesNotContain("idCard")
            .doesNotContain("identityNumber");
    }

    @Test
    void finalReportAlwaysUsesTrustedServerIdentity() {
        AgentAnalysis analysis = new AgentAnalysis("低风险", "tx", "corp", List.of(), List.of("LEGAL-1"),
                List.of("normal"), "conclusion", List.of("LEGAL-1"), false, List.of("NORMAL_TRANSACTION_PATTERN"),
                List.of("MAINTAIN_STANDARD_MONITORING"));

        DueDiligenceReport report = DueDiligenceReport.fromAnalysis("C-TRUSTED", "可信客户", analysis);

        assertThat(report.customerId()).isEqualTo("C-TRUSTED");
        assertThat(report.customerName()).isEqualTo("可信客户");
    }

    @Test
    @SuppressWarnings("deprecation") // 验证保留的评测兼容别名与生产词表仍共享同一实例。
    void evaluationVocabularyAliasesProductionVocabularyWithoutCopyDrift() {
        assertThat(AgentEvalVocabulary.FINDING_CODES).isSameAs(AgentReportVocabulary.FINDING_CODES);
        assertThat(AgentEvalVocabulary.ACTION_CODES).isSameAs(AgentReportVocabulary.ACTION_CODES);
    }

}
