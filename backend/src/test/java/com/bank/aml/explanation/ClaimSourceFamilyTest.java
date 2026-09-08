package com.bank.aml.explanation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1-2 / RF-20：来源家族独立性——同源文件复制、转发、模型摘要不增加独立确认数；
 * 独立确认计数按 sourceFamily（sourceSystem + 归一化 sourceReference）去重。
 */
class ClaimSourceFamilyTest {

    /** 同一 artifactKey 不同抓取版本（v1/v2）→ 同一家族；不同引用 → 不同家族。 */
    @Test
    void sameSourceReferenceFormsOneFamilyAcrossVersions() {
        EvidenceArtifactVersion v1 = artifact("CORE_BANKING", "TXN-DOC-001");
        v1.setVersion(1);
        EvidenceArtifactVersion v2 = artifact("CORE_BANKING", "TXN-DOC-001");
        v2.setVersion(2);
        EvidenceArtifactVersion other = artifact("CORE_BANKING", "TXN-DOC-002");

        assertThat(ExplanationClaimService.sourceFamilyOf(v1))
                .isEqualTo(ExplanationClaimService.sourceFamilyOf(v2))
                .isEqualTo("core_banking:txn-doc-001");
        assertThat(ExplanationClaimService.sourceFamilyOf(other))
                .isNotEqualTo(ExplanationClaimService.sourceFamilyOf(v1));
    }

    /** 相同内容不同来源系统 → 不同家族（来源系统是家族身份的一部分）。 */
    @Test
    void differentSystemsAreDifferentFamilies() {
        EvidenceArtifactVersion core = artifact("CORE_BANKING", "DOC-1");
        EvidenceArtifactVersion kyc = artifact("KYC_PLATFORM", "DOC-1");
        assertThat(ExplanationClaimService.sourceFamilyOf(core))
                .isNotEqualTo(ExplanationClaimService.sourceFamilyOf(kyc));
    }

    /** 名称大小写/空白归一：同引用不同大小写不产生虚假的"第二来源"。 */
    @Test
    void referenceNormalizationPreventsFalseIndependence() {
        EvidenceArtifactVersion lower = artifact("CORE_BANKING", "txn-doc-001");
        EvidenceArtifactVersion upper = artifact("core_banking", "TXN-DOC-001");
        assertThat(ExplanationClaimService.sourceFamilyOf(lower))
                .isEqualTo(ExplanationClaimService.sourceFamilyOf(upper));
    }

    private EvidenceArtifactVersion artifact(String system, String reference) {
        EvidenceArtifactVersion entity = new EvidenceArtifactVersion();
        entity.setCaseId(1L);
        entity.setArtifactKey((system + ":" + reference).toLowerCase());
        entity.setSourceSystem(system);
        entity.setSourceReference(reference);
        entity.setAvailability("RESOLVED");
        entity.setContentSha256("a".repeat(64));
        return entity;
    }
}
