package com.bank.aml.agent;

import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.risk.RiskContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReportStabilizerTest {

    @Test
    void restoresOnlyFrozenEvidenceToBothCitationFields() {
        DueDiligenceReport raw = report(
                List.of("模型遗漏了证据编号"),
                List.of("交易工具已完成"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(snapshot(), raw);

        assertThat(stabilized.legalBasis()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
        assertThat(stabilized.evidenceChain()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
        assertThat(stabilized.riskLevel()).isEqualTo(raw.riskLevel());
        assertThat(stabilized.findingCodes()).isEqualTo(raw.findingCodes());
        assertThat(stabilized.actionCodes()).isEqualTo(raw.actionCodes());
    }

    @Test
    void doesNotRemoveInventedEvidenceSoValidatorCanRejectIt() {
        DueDiligenceReport raw = report(
                List.of("依据 LEGAL-INVENTED"),
                List.of("证据 LEGAL-INVENTED"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(snapshot(), raw);

        assertThat(stabilized.legalBasis()).contains("依据 LEGAL-INVENTED");
        assertThat(stabilized.evidenceChain()).contains("证据 LEGAL-INVENTED");
        assertThat(stabilized.legalBasis()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
        assertThat(stabilized.evidenceChain()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
    }

    @Test
    void leavesNullCollectionsUntouchedWhenNoFrozenEvidenceExists() {
        InvestigationSnapshot noEvidence = new InvestigationSnapshot(
                "snapshot-empty", 1L, 1, Instant.EPOCH,
                new CustomerProfile("C1", "客户", "ID", "个人", "", "", ""),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(),
                risk(), "v1", "digest");
        DueDiligenceReport malformed = new DueDiligenceReport(
                "C1", "客户", "低风险", "tx", "corp", List.of(), null,
                List.of("point"), "conclusion", null, false,
                List.of("NO_SANCTION_HIT"), List.of("MAINTAIN_STANDARD_MONITORING"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(noEvidence, malformed);

        assertThat(stabilized).isSameAs(malformed);
    }

    @Test
    void doesNotAttachEvidenceThatTheLegalToolDidNotReturn() {
        DueDiligenceReport raw = report(List.of("未引用法规"), List.of("仅交易证据"));

        DueDiligenceReport stabilized = AgentReportStabilizer
                .attachFrozenLegalEvidence(snapshot(), raw, List.of());

        assertThat(stabilized).isSameAs(raw);
        assertThat(stabilized.legalBasis()).doesNotContain("LEGAL-VALID-1");
        assertThat(stabilized.evidenceChain()).doesNotContain("LEGAL-VALID-1");
    }

    private DueDiligenceReport report(List<String> basis, List<String> chain) {
        return new DueDiligenceReport(
                "C1", "客户", "低风险", "tx", "corp", List.of(), basis,
                List.of("交易正常"), "常规监测", chain, false,
                List.of("NO_SANCTION_HIT"), List.of("MAINTAIN_STANDARD_MONITORING"));
    }

    private InvestigationSnapshot snapshot() {
        LegalDoc doc = new LegalDoc("LEGAL-VALID-1", "反洗钱法", "文号", "第三条", "尽职调查要求");
        return new InvestigationSnapshot(
                "snapshot-1", 1L, 1, Instant.EPOCH,
                new CustomerProfile("C1", "客户", "ID", "个人", "", "", ""),
                List.of(), List.of(), List.of(), List.of(doc), Map.of("尽调", List.of(doc)), List.of("尽调"),
                risk(), "v1", "digest");
    }

    private RiskContext risk() {
        return new RiskContext(0, false, 0, 0, 0, true, true, 0, 0, "低风险", 1);
    }
}
