package com.bank.aml.explanation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 事实-证据关联数据访问（v3 计划 §9）。 */
public interface ClaimEvidenceLinkRepository extends JpaRepository<ClaimEvidenceLink, Long> {

    List<ClaimEvidenceLink> findByClaimIdOrderByIdAsc(Long claimId);

    List<ClaimEvidenceLink> findByCaseIdAndArtifactVersionId(Long caseId, Long artifactVersionId);

}
