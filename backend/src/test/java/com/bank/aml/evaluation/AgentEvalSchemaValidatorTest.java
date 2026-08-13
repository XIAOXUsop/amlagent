package com.bank.aml.evaluation;

import com.bank.aml.agent.DueDiligenceReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalSchemaValidatorTest {

    private final AgentEvalSchemaValidator validator = new AgentEvalSchemaValidator();
    private final AgentEvalDataset.AgentEvalCase evalCase = evalCase();

    @Test
    void acceptsCompleteStructuredReport() {
        assertThat(validator.validate(evalCase, validReport())).isEmpty();
    }

    @Test
    void rejectsIdentityMismatchAndUnknownCodes() {
        DueDiligenceReport invalid = new DueDiligenceReport(
                "OTHER", "Other Name", "LOW", "tx", "corp", List.of(),
                List.of("law"), List.of("risk"), "conclusion", List.of("evidence"),
                true, List.of("MADE_UP_FINDING"), List.of("MAINTAIN_STANDARD_MONITORING")
        );

        assertThat(validator.validate(evalCase, invalid)).contains(
                "CUSTOMER_ID_MISMATCH", "CUSTOMER_NAME_MISMATCH", "RISK_LEVEL_INVALID",
                "FINDING_CODES_OUT_OF_VOCABULARY", "MANUAL_REVIEW_ACTION_INCONSISTENT"
        );
    }

    private DueDiligenceReport validReport() {
        return new DueDiligenceReport(
                "E1001", "Alice", "低风险", "tx", "corp", List.of(),
                List.of("law"), List.of("risk"), "conclusion", List.of("evidence"),
                false, List.of("NORMAL_TRANSACTION_PATTERN"),
                List.of("MAINTAIN_STANDARD_MONITORING")
        );
    }

    private AgentEvalDataset.AgentEvalCase evalCase() {
        var input = new AgentEvalDataset.AgentInput(
                "E1001", "Alice", "ID-1", "个人", "2026-08-01", "alert", "case");
        var facts = new AgentEvalDataset.RiskFacts(0, 0, 0, true, true, 0, 0, false, 0);
        var fixture = new AgentEvalDataset.ToolFixture(
                "tx", "corp", "sanction", "query", List.of("query"), "legal", facts);
        var expected = new AgentEvalDataset.ExpectedOutcome(
                "低风险", false,
                List.of("transactionProfile", "corporateProfile", "checkSanctions", "searchLegal"),
                List.of("normal"), List.of("NORMAL_TRANSACTION_PATTERN"),
                List.of("NORMAL_TRANSACTION_PATTERN"), List.of("MAINTAIN_STANDARD_MONITORING"),
                List.of("MAINTAIN_STANDARD_MONITORING"), List.of("CDD"),
                List.of("FABRICATED_SANCTION_HIT")
        );
        var annotation = new AgentEvalDataset.Annotation(
                "rationale", List.of("fixture"), "PENDING_DOMAIN_REVIEW", "review");
        return new AgentEvalDataset.AgentEvalCase(
                "CASE-1", "DEV", "NORMAL", "EASY", input, fixture, expected, annotation);
    }
}
