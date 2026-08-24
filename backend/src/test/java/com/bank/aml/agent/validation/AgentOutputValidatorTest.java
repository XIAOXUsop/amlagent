package com.bank.aml.agent.validation;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.risk.RiskContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOutputValidatorTest {

    private final AgentOutputValidator validator = new AgentOutputValidator();

    @Test
    void acceptsEvidenceGroundedReport() {
        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), validReport()).valid()).isTrue();
    }

    @Test
    void rejectsMalformedAndOutOfVocabularyOutput() {
        DueDiligenceReport report = new DueDiligenceReport(
                "C001", "可信客户", "HIGH", "", "corp", null,
                List.of(), List.of(), "", List.of(), null,
                List.of("MADE_UP"), List.of("MADE_UP_ACTION"));

        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), report).violations())
                .contains("RISK_LEVEL_INVALID", "TRANSACTION_PROFILE_BLANK", "SANCTIONS_NULL",
                        "LEGAL_BASIS_EMPTY", "RISK_POINTS_EMPTY", "CONCLUSION_BLANK",
                        "EVIDENCE_CHAIN_EMPTY", "MANUAL_REVIEW_REQUIRED_NULL",
                        "FINDING_CODES_OUT_OF_VOCABULARY", "ACTION_CODES_OUT_OF_VOCABULARY");
    }

    @Test
    void rejectsEmptyFindingAndActionCollections() {
        DueDiligenceReport r = validReport();
        DueDiligenceReport empty = copy(r, false, List.of(), List.of());

        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), empty).violations())
                .contains("FINDING_CODES_EMPTY", "ACTION_CODES_EMPTY");
    }

    @Test
    void rejectsInventedOrOneSidedLegalEvidenceIds() {
        DueDiligenceReport invented = reportWithEvidence(
                List.of("依据 LEGAL-INVENTED"), List.of("LEGAL-INVENTED"));
        DueDiligenceReport oneSided = reportWithEvidence(
                List.of("依据 LEGAL-VALID-1"), List.of("仅有交易证据"));

        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), invented).violations())
                .contains("EVIDENCE_ID_NOT_IN_SNAPSHOT:LEGAL-INVENTED", "LEGAL_EVIDENCE_ID_MISSING");
        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), oneSided).violations())
                .contains("LEGAL_EVIDENCE_ID_MISSING");
    }

    @Test
    void rejectsContradictoryManualReviewState() {
        DueDiligenceReport report = copy(validReport(), false,
                List.of("MAINTAIN_STANDARD_MONITORING", "MANUAL_REVIEW"),
                validReport().findingCodes());

        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), report).violations())
                .contains("MANUAL_REVIEW_ACTION_INCONSISTENT");
    }

    @Test
    void rejectsHighImpactClaimsWithoutSnapshotFacts() {
        DueDiligenceReport report = copy(validReport(), false,
                List.of("FREEZE_ASSETS"),
                List.of("NO_SANCTION_HIT", "SANCTION_LEVEL_1_MATCH", "UBO_UNVERIFIED",
                        "TRANSACTION_DATA_UNAVAILABLE", "CROSS_BORDER_ACTIVITY", "STRUCTURING_PATTERN"));

        assertThat(validator.validate(snapshot(true, 2, true, 0, 0, 0), report).violations())
                .contains("NO_SANCTION_HIT_UNSUPPORTED", "SANCTION_LEVEL_1_MATCH_UNSUPPORTED",
                        "UBO_UNVERIFIED_UNSUPPORTED", "TRANSACTION_DATA_UNAVAILABLE_UNSUPPORTED",
                        "CROSS_BORDER_ACTIVITY_UNSUPPORTED", "HIGH_RISK_TRANSACTION_PATTERN_UNSUPPORTED",
                        "FREEZE_ASSETS_UNSUPPORTED");
    }

    @Test
    void allowsLevelOneSanctionFreezeWhenFactsSupportIt() {
        DueDiligenceReport report = copy(validReport(), true,
                List.of("FREEZE_ASSETS", "MANUAL_REVIEW"),
                List.of("SANCTION_LEVEL_1_MATCH"));

        assertThat(validator.validate(snapshot(true, 1, true, 0, 0, 0), report).valid()).isTrue();
    }

    @Test
    void rejectsCriticalActionWhenCitedLawDoesNotSupportTheAction() {
        DueDiligenceReport report = copy(validReport(), true,
                List.of("REPORT_TO_AUTHORITY", "MANUAL_REVIEW"),
                List.of("RISK_ASSESSMENT_UNCERTAIN"));

        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), report).violations())
                .contains("REPORT_TO_AUTHORITY_LEGAL_SUPPORT_MISSING");
    }

    @Test
    void rejectsIdentityCopiedIntoFreeTextWithoutLoggingTheSensitiveValue() {
        DueDiligenceReport r = validReport();
        DueDiligenceReport leaked = new DueDiligenceReport(
                r.customerId(), r.customerName(), r.riskLevel(),
                "客户 C001 的证件 ID-1 已核验", r.corporateProfile(), r.sanctions(),
                r.legalBasis(), r.riskPoints(), r.conclusion(), r.evidenceChain(),
                r.manualReviewRequired(), r.findingCodes(), r.actionCodes());

        assertThat(validator.validate(snapshot(false, 0, true, 0, 0, 0), leaked).violations())
                .containsExactly("IDENTITY_DATA_LEAKED")
                .allSatisfy(code -> assertThat(code).doesNotContain("C001", "ID-1", "可信客户"));
    }

    private DueDiligenceReport validReport() {
        return new DueDiligenceReport("C001", "可信客户", "低风险", "tx", "corp", List.of(),
                List.of("依据 LEGAL-VALID-1"), List.of("交易正常"), "维持常规监测",
                List.of("法规证据 LEGAL-VALID-1"), false,
                List.of("NORMAL_TRANSACTION_PATTERN", "NO_SANCTION_HIT"),
                List.of("MAINTAIN_STANDARD_MONITORING"));
    }

    private DueDiligenceReport reportWithEvidence(List<String> basis, List<String> chain) {
        DueDiligenceReport r = validReport();
        return new DueDiligenceReport(r.customerId(), r.customerName(), r.riskLevel(), r.transactionProfile(),
                r.corporateProfile(), r.sanctions(), basis, r.riskPoints(), r.conclusion(), chain,
                r.manualReviewRequired(), r.findingCodes(), r.actionCodes());
    }

    private DueDiligenceReport copy(DueDiligenceReport r, boolean manual, List<String> actions,
                                    List<String> findings) {
        return new DueDiligenceReport(r.customerId(), r.customerName(), r.riskLevel(), r.transactionProfile(),
                r.corporateProfile(), r.sanctions(), r.legalBasis(), r.riskPoints(), r.conclusion(),
                r.evidenceChain(), manual, findings, actions);
    }

    private InvestigationSnapshot snapshot(boolean sanctionHit, int severity, boolean dataComplete,
                                           double crossRatio, int patternSeverity, int uboSeverity) {
        RiskContext facts = new RiskContext(severity, sanctionHit, crossRatio, 0, 0,
                dataComplete, false, patternSeverity, uboSeverity, "低风险", 1);
        return new InvestigationSnapshot("snapshot-1", 1L, 1, Instant.parse("2026-08-01T00:00:00Z"),
                new CustomerProfile("C001", "可信客户", "ID-1", "个人", "", "", ""),
                List.of(), List.of(), List.of(),
                List.of(new LegalDoc("LEGAL-VALID-1", "涉及恐怖活动资产冻结管理办法", "文号", "第二条",
                        "命中恐怖活动或制裁名单后，应当立即采取冻结措施。高风险客户应开展强化尽职调查。")),
                java.util.Map.of("尽职调查", List.of(new LegalDoc("LEGAL-VALID-1", "涉及恐怖活动资产冻结管理办法", "文号", "第二条",
                        "命中恐怖活动或制裁名单后，应当立即采取冻结措施。高风险客户应开展强化尽职调查。"))),
                List.of("尽职调查"), facts, "legal-v1", "digest");
    }
}
