package com.bank.aml.agent;

import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.RiskContext;
import com.bank.aml.evidence.LegalDoc;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReportStabilizerTest {

    @Test
    void restoresOnlyFrozenEvidenceToBothCitationFields() {
        DueDiligenceReport raw = report(List.of("模型遗漏了证据编号"), List.of("交易工具已完成"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(snapshot(), raw);

        assertThat(stabilized.legalBasis()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
        assertThat(stabilized.evidenceChain()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
        assertThat(stabilized.riskLevel()).isEqualTo(raw.riskLevel());
        assertThat(stabilized.findingCodes()).isEqualTo(raw.findingCodes());
        assertThat(stabilized.actionCodes()).isEqualTo(raw.actionCodes());
    }

    @Test
    void doesNotRemoveInventedEvidenceSoValidatorCanRejectIt() {
        DueDiligenceReport raw = report(List.of("依据 LEGAL-INVENTED"), List.of("证据 LEGAL-INVENTED"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(snapshot(), raw);

        assertThat(stabilized.legalBasis()).contains("依据 LEGAL-INVENTED");
        assertThat(stabilized.evidenceChain()).contains("证据 LEGAL-INVENTED");
        assertThat(stabilized.legalBasis()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
        assertThat(stabilized.evidenceChain()).anyMatch(value -> value.contains("LEGAL-VALID-1"));
    }

    @Test
    void leavesNullCollectionsUntouchedWhenNoFrozenEvidenceExists() {
        InvestigationSnapshot noEvidence = new InvestigationSnapshot("snapshot-empty", 1L, 1, Instant.EPOCH,
                new CustomerProfile("C1", "客户", "ID", "个人", "", "", ""), List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), risk(), "v1", "digest");
        DueDiligenceReport malformed = new DueDiligenceReport("C1", "客户", "低风险", "tx", "corp", List.of(), null,
                List.of("point"), "conclusion", null, false, List.of("NO_SANCTION_HIT"),
                List.of("MAINTAIN_STANDARD_MONITORING"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(noEvidence, malformed);

        assertThat(stabilized).isSameAs(malformed);
    }

    @Test
    void doesNotAttachEvidenceThatTheLegalToolDidNotReturn() {
        DueDiligenceReport raw = report(List.of("未引用法规"), List.of("仅交易证据"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(snapshot(), raw, List.of());

        assertThat(stabilized).isSameAs(raw);
        assertThat(stabilized.legalBasis()).doesNotContain("LEGAL-VALID-1");
        assertThat(stabilized.evidenceChain()).doesNotContain("LEGAL-VALID-1");
    }

    @Test
    void attachesEveryEvidenceActuallyReturnedByLegalToolWhenModelCitesOnlyOne() {
        LegalDoc first = new LegalDoc("LEGAL-VALID-1", "反洗钱法", "文号", "第三条", "应当报告");
        LegalDoc second = new LegalDoc("LEGAL-VALID-2", "资产冻结办法", "文号", "第二条", "应当立即冻结");
        InvestigationSnapshot snapshot = new InvestigationSnapshot("snapshot-2", 1L, 1, Instant.EPOCH,
                new CustomerProfile("C1", "客户", "ID", "个人", "", "", ""), List.of(), List.of(), List.of(),
                List.of(first, second), Map.of("制裁", List.of(first, second)), List.of("制裁"), risk(), "v1", "digest");
        DueDiligenceReport raw = report(List.of("依据 LEGAL-VALID-1"), List.of("证据 LEGAL-VALID-1"));

        DueDiligenceReport stabilized = AgentReportStabilizer.attachFrozenLegalEvidence(snapshot, raw,
                List.of("LEGAL-VALID-1", "LEGAL-VALID-2"));

        assertThat(stabilized.legalBasis()).anyMatch(value -> value.contains("LEGAL-VALID-2"));
        assertThat(stabilized.evidenceChain()).anyMatch(value -> value.contains("LEGAL-VALID-2"));
    }

    private DueDiligenceReport report(List<String> basis, List<String> chain) {
        return new DueDiligenceReport("C1", "客户", "低风险", "tx", "corp", List.of(), basis, List.of("交易正常"), "常规监测", chain,
                false, List.of("NO_SANCTION_HIT"), List.of("MAINTAIN_STANDARD_MONITORING"));
    }

    private InvestigationSnapshot snapshot() {
        LegalDoc doc = new LegalDoc("LEGAL-VALID-1", "反洗钱法", "文号", "第三条", "尽职调查要求");
        return new InvestigationSnapshot("snapshot-1", 1L, 1, Instant.EPOCH,
                new CustomerProfile("C1", "客户", "ID", "个人", "", "", ""), List.of(), List.of(), List.of(), List.of(doc),
                Map.of("尽调", List.of(doc)), List.of("尽调"), risk(), "v1", "digest");
    }

    private RiskContext risk() {
        return new RiskContext(0, false, 0, 0, 0, true, true, 0, 0, "低风险", 1);
    }

}
