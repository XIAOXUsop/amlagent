package com.bank.aml.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnalysisPrivacyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void modelOutputContractContainsNoTrustedIdentityFields() throws Exception {
        AgentAnalysis analysis = new AgentAnalysis(
                "低风险", "tx", "corp", List.of(), List.of("LEGAL-1"), List.of("normal"),
                "conclusion", List.of("LEGAL-1"), false,
                List.of("NORMAL_TRANSACTION_PATTERN"), List.of("MAINTAIN_STANDARD_MONITORING"));

        String json = objectMapper.writeValueAsString(analysis);

        assertThat(json)
                .doesNotContain("customerId")
                .doesNotContain("customerName")
                .doesNotContain("idCard")
                .doesNotContain("identityNumber");
    }

    @Test
    void finalReportAlwaysUsesTrustedServerIdentity() {
        AgentAnalysis analysis = new AgentAnalysis(
                "低风险", "tx", "corp", List.of(), List.of("LEGAL-1"), List.of("normal"),
                "conclusion", List.of("LEGAL-1"), false,
                List.of("NORMAL_TRANSACTION_PATTERN"), List.of("MAINTAIN_STANDARD_MONITORING"));

        DueDiligenceReport report = DueDiligenceReport.fromAnalysis("C-TRUSTED", "可信客户", analysis);

        assertThat(report.customerId()).isEqualTo("C-TRUSTED");
        assertThat(report.customerName()).isEqualTo("可信客户");
    }

    @Test
    void evaluationVocabularyAliasesProductionVocabularyWithoutCopyDrift() {
        assertThat(com.bank.aml.evaluation.AgentEvalVocabulary.FINDING_CODES)
                .isSameAs(AgentReportVocabulary.FINDING_CODES);
        assertThat(com.bank.aml.evaluation.AgentEvalVocabulary.ACTION_CODES)
                .isSameAs(AgentReportVocabulary.ACTION_CODES);
    }
}
