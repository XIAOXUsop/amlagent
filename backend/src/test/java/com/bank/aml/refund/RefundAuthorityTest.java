package com.bank.aml.refund;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-2 / RF-06~10：退款收款权限三分支 + 影响评估。
 * 原路不自动免责（RF-07）；买方权限需核实（RF-08/09）；无关账户权限未知阻断（RF-09/10）；
 * 影响评估四类全覆盖、禁止"无影响"。
 */
class RefundAuthorityTest {

    private final RefundAuthorityService service = new RefundAuthorityService();

    /** RF-06：原路退款 + 原因已核验 → 可提交（正常对照）。 */
    @Test
    void originalPathWithVerifiedReasonIsAdmissible() {
        var verdict = service.assess(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.VERIFIED);
        assertThat(verdict.admissible()).isTrue();
        assertThat(service.assessImpact(new RefundAuthorityService.ImpactInput(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.VERIFIED, false, false, false)))
                .isEqualTo(RefundAuthorityService.ImpactType.INFORMATIONAL);
    }

    /** RF-07：原路退款但商业原因无证据 → 原路不自动免责。 */
    @Test
    void originalPathWithoutReasonIsBlocked() {
        var verdict = service.assess(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.MISSING);
        assertThat(verdict.admissible()).isFalse();
        assertThat(verdict.blockerCode()).isEqualTo("REFUND_REASON_UNRESOLVED");
        assertThat(verdict.explanation()).contains("原路退款不自动免责");
        assertThat(service.assessImpact(new RefundAuthorityService.ImpactInput(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.UNVERIFIED, false, false, false)))
                .isEqualTo(RefundAuthorityService.ImpactType.SUPPLEMENT_REQUIRED);
    }

    /** RF-08：退给 B 且权限已核实 → 可解释（记录权限范围）。 */
    @Test
    void buyerWithVerifiedAuthorityIsAdmissible() {
        var verdict = service.assess(
                RefundAuthorityService.RecipientAuthority.BUYER_VERIFIED,
                RefundAuthorityService.CommercialReason.VERIFIED);
        assertThat(verdict.admissible()).isTrue();
        assertThat(verdict.explanation()).contains("RF-08");
    }

    /** RF-09：退给 B 仅声明同集团 → 权限未知阻断。 */
    @Test
    void unresolvedAuthorityBlocksExplanation() {
        var verdict = service.assess(
                RefundAuthorityService.RecipientAuthority.UNRESOLVED,
                RefundAuthorityService.CommercialReason.VERIFIED);
        assertThat(verdict.admissible()).isFalse();
        assertThat(verdict.blockerCode()).isEqualTo("RECIPIENT_AUTHORITY_UNRESOLVED");
        assertThat(verdict.explanation()).contains("身份本身不足以证明收款权限");
    }

    /** RF-10：付款主体否认安排 → 权限矛盾阻断（不自动变可疑）。 */
    @Test
    void contradictedAuthorityBlocksAndKeepsContradiction() {
        var verdict = service.assess(
                RefundAuthorityService.RecipientAuthority.CONTRADICTED,
                RefundAuthorityService.CommercialReason.VERIFIED);
        assertThat(verdict.admissible()).isFalse();
        assertThat(verdict.blockerCode()).isEqualTo("RECIPIENT_AUTHORITY_CONTRADICTED");
        assertThat(verdict.explanation()).contains("不覆盖旧授权原文");
        assertThat(verdict.explanation()).doesNotContain("自动确认可疑");
    }

    /** 影响评估：核验失去支持 → 否定旧支持事实；来源缺口 → 更正路径。 */
    @Test
    void impactAssessmentCoversAllFourTypes() {
        assertThat(service.assessImpact(new RefundAuthorityService.ImpactInput(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.VERIFIED, false, false, true)))
                .isEqualTo(RefundAuthorityService.ImpactType.CONTRADICTS_ADOPTED);
        assertThat(service.assessImpact(new RefundAuthorityService.ImpactInput(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.VERIFIED, false, true, false)))
                .isEqualTo(RefundAuthorityService.ImpactType.SOURCE_CORRECTED);
        assertThat(service.assessImpact(new RefundAuthorityService.ImpactInput(
                RefundAuthorityService.RecipientAuthority.ORIGINAL_PAYER_VERIFIED,
                RefundAuthorityService.CommercialReason.VERIFIED, true, false, false)))
                .isEqualTo(RefundAuthorityService.ImpactType.SOURCE_CORRECTED);
    }

    /** 三分支语义表与固定案例一致。 */
    @Test
    void branchSemanticsMatchFixedCase() {
        assertThat(RefundAuthorityService.branchSemantics())
                .containsKeys("ORIGINAL_PATH", "TO_BUYER", "TO_UNRELATED");
    }
}
