package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.validation.AgentOutputValidator;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.risk.RiskContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedReporterTest {

    private final RuleBasedReporter reporter = new RuleBasedReporter();
    private final AgentOutputValidator validator = new AgentOutputValidator();

    @Test
    void safetyHoldIsStructuredAndCannotRetainNormalFinding() {
        InvestigationSnapshot snapshot = snapshot(new RiskContext(
                0, false, 0, 0, 0, true, false, 0, 0, "低风险", 1));

        DueDiligenceReport report = reporter.generateSafetyHold(
                snapshot, "常规复核", List.of("RISK_LEVEL_INVALID"));

        assertThat(report.riskLevel()).isEqualTo("中风险");
        assertThat(report.manualReviewRequired()).isTrue();
        assertThat(report.findingCodes()).contains("RISK_ASSESSMENT_UNCERTAIN")
                .doesNotContain("NORMAL_TRANSACTION_PATTERN");
        assertThat(report.actionCodes()).contains("MANUAL_REVIEW", "ENHANCED_DUE_DILIGENCE");
        assertThat(validator.validate(snapshot, report).valid()).isTrue();
    }

    @Test
    void anomalousSanctionSeverityProducesUncertaintyInsteadOfFalseWatchlistClaim() {
        InvestigationSnapshot snapshot = snapshot(new RiskContext(
                0, true, 0, 0, 0, true, false, 0, 0, "低风险", 1));

        DueDiligenceReport report = reporter.generate(snapshot, "名单命中");

        assertThat(report.findingCodes()).contains("RISK_ASSESSMENT_UNCERTAIN")
                .doesNotContain("SANCTION_LEVEL_1_MATCH", "DOMESTIC_WATCHLIST_MATCH");
    }

    private InvestigationSnapshot snapshot(RiskContext facts) {
        return new InvestigationSnapshot("snapshot-1", 1L, 1, Instant.parse("2026-08-01T00:00:00Z"),
                new CustomerProfile("C001", "可信客户", "ID-1", "个人", "", "", ""),
                List.of(), List.of(), List.of(),
                List.of(new LegalDoc("LEGAL-VALID-1", "法规", "文号", "第一条", "内容")),
                java.util.Map.of("尽职调查", List.of(new LegalDoc("LEGAL-VALID-1", "法规", "文号", "第一条", "内容"))),
                List.of("尽职调查"), facts, "legal-v1", "digest");
    }
}
