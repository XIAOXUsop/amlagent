package com.bank.aml.explanation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceArtifactVersionRepository extends JpaRepository<EvidenceArtifactVersion, Long> {

    Optional<EvidenceArtifactVersion> findByIdAndCaseId(Long id, Long caseId);

    Optional<EvidenceArtifactVersion> findTopByCaseIdAndArtifactKeyOrderByVersionDesc(Long caseId, String artifactKey);

    List<EvidenceArtifactVersion> findByCaseIdOrderByCapturedAtAsc(Long caseId);

}
