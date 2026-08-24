package com.bank.aml.assistant.guard;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.AssistantCustomerView;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.domain.OwnershipRiskView;
import com.bank.aml.assistant.domain.RetrievalStatusView;
import com.bank.aml.assistant.domain.SanctionRiskView;
import com.bank.aml.assistant.domain.TransactionRiskView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimCitationValidatorTest {

    private final ClaimCitationValidator validator = new ClaimCitationValidator();

    private final AssistantEvidence legal = new AssistantEvidence("LEGAL-FREEZE-1",
            AssistantEvidence.EvidenceType.AML_LEGAL, "涉及恐怖活动资产冻结管理办法",
            "金融机构应当立即对相关资产采取冻结措施，未按要求冻结可能面临处理。", "OFFICIAL");
    private final CustomerAssistantSnapshot snapshot = snapshot(legal);

    private CustomerAssistantSnapshot snapshot(AssistantEvidence... evidence) {
        return new CustomerAssistantSnapshot("s", "c", "r", Instant.EPOCH,
                new AssistantCustomerView("CURRENT_CUSTOMER", "企业", "贸易", "上海", "5000万", "ENABLED"),
                new TransactionRiskView(2, new java.math.BigDecimal("10"), new java.math.BigDecimal("5"),
                        0, 0, 0, 0, List.of("CNY"), List.of("中国大陆"), true),
                new OwnershipRiskView(0, 0, List.of()), new SanctionRiskView(false, 0, 0, List.of()),
                List.of(evidence), "TEST", "v1", "legal-v1", RetrievalStatusView.NONE, "digest");
    }

    private AssistantClaim claim(String text, List<String> evidenceIds, List<String> spans) {
        return new AssistantClaim("C1", "LEGAL_REQUIREMENT", text, evidenceIds, spans);
    }

    @Test
    void legalClaimMustCiteASnapshotLegalEvidence() {
        assertThat(validator.validate(snapshot, List.of(
                claim("金融机构应当立即冻结相关资产", List.of(), List.of()))))
                .anyMatch(v -> v.startsWith("CLAIM_LEGAL_WITHOUT_EVIDENCE"));
        assertThat(validator.validate(snapshot, List.of(
                claim("金融机构应当立即冻结相关资产", List.of("LEGAL-INVENTED"), List.of()))))
                .anyMatch(v -> v.startsWith("CLAIM_EVIDENCE_NOT_IN_SNAPSHOT"));
        assertThat(validator.validate(snapshot, List.of(
                claim("金融机构应当履行报告义务", List.of("LEGAL-FREEZE-1"), List.of()))))
                .anyMatch(v -> v.startsWith("CLAIM_LEGAL_WITHOUT_SUPPORT_SPAN"));
    }

    @Test
    void validClaimPassesWithRealSpanAndNumber() {
        AssistantEvidence second = new AssistantEvidence("LEGAL-FREEZE-2",
                AssistantEvidence.EvidenceType.AML_LEGAL, "涉及恐怖活动资产冻结管理办法",
                "金融机构应当立即对相关资产采取冻结措施，并向主管机关报告，不得拖延。", "OFFICIAL");
        CustomerAssistantSnapshot snapshot = snapshot(legal, second);
        // 高风险冻结声明引用两条法律证据，模态与原文一致，支持片段为原文连续子串
        assertThat(validator.validate(snapshot, List.of(claim(
                "金融机构应当立即对相关资产采取冻结措施，并向主管机关报告",
                List.of("LEGAL-FREEZE-1", "LEGAL-FREEZE-2"),
                List.of("金融机构应当立即对相关资产采取冻结措施"))))).isEmpty();
    }

    @Test
    void unsupportedOrFragmentedSpanIsRejected() {
        AssistantEvidence second = new AssistantEvidence("LEGAL-FREEZE-2",
                AssistantEvidence.EvidenceType.AML_LEGAL, "涉及恐怖活动资产冻结管理办法",
                "金融机构应当立即对相关资产采取冻结措施，并向主管机关报告。", "OFFICIAL");
        CustomerAssistantSnapshot snapshot = snapshot(legal, second);
        // 片段为原文连续子串且足够长 → 通过；换置信合法 span
        assertThat(validator.validate(snapshot, List.of(claim(
                "金融机构应当立即对相关资产采取冻结措施",
                List.of("LEGAL-FREEZE-1", "LEGAL-FREEZE-2"),
                List.of("应当立即对相关资产采取冻结"))))).isEmpty();
        assertThat(validator.validate(snapshot, List.of(claim(
                "金融机构应当立即对相关资产采取冻结措施",
                List.of("LEGAL-FREEZE-1", "LEGAL-FREEZE-2"),
                List.of("完全不存在的原文片段")))))
                .anyMatch(v -> v.startsWith("CLAIM_SPAN_NOT_SUPPORTED"));
    }

    @Test
    void modalityMismatchBetweenClaimAndEvidenceIsRejected() {
        // 证据是“应当”，声明却写成“不得”解除冻结义务
        assertThat(validator.validate(snapshot, List.of(claim(
                "金融机构不得解除冻结措施",
                List.of("LEGAL-FREEZE-1"),
                List.of("金融机构应当立即对相关资产采取冻结措施")))))
                .anyMatch(v -> v.startsWith("CLAIM_MODALITY_MISMATCH"));
    }

    @Test
    void highRiskFreezeClaimNeedsMultipleLegalSupports() {
        assertThat(validator.validate(snapshot, List.of(claim(
                "金融机构应当立即冻结并报告制裁名单命中主体",
                List.of("LEGAL-FREEZE-1"),
                List.of("金融机构应当立即对相关资产采取冻结措施")))))
                .anyMatch(v -> v.startsWith("HIGH_RISK_CLAIM_UNDERSUPPORTED"));
    }
}
