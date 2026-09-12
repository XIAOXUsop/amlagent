package com.bank.aml.explanation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplanationEvidenceUseRepository extends JpaRepository<ExplanationEvidenceUse, Long> {

    List<ExplanationEvidenceUse> findBySubmissionIdOrderByIdAsc(Long submissionId);

    List<ExplanationEvidenceUse> findByCaseIdAndArtifactVersionId(Long caseId, Long artifactVersionId);

}
