package com.bank.aml.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvidenceArtifactVersionRepository extends JpaRepository<EvidenceArtifactVersion, Long> {

    Optional<EvidenceArtifactVersion> findByIdAndCaseId(Long id, Long caseId);

    Optional<EvidenceArtifactVersion> findTopByCaseIdAndArtifactKeyOrderByVersionDesc(
            Long caseId, String artifactKey);

    List<EvidenceArtifactVersion> findByCaseIdOrderByCapturedAtAsc(Long caseId);
}
