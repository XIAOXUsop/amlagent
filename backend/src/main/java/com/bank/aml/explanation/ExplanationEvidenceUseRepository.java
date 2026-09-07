package com.bank.aml.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExplanationEvidenceUseRepository extends JpaRepository<ExplanationEvidenceUse, Long> {

    List<ExplanationEvidenceUse> findBySubmissionIdOrderByIdAsc(Long submissionId);

    List<ExplanationEvidenceUse> findByCaseIdAndArtifactVersionId(Long caseId, Long artifactVersionId);
}
