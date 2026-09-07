package com.bank.aml.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceVerificationEventRepository extends JpaRepository<EvidenceVerificationEvent, Long> {

    List<EvidenceVerificationEvent> findByCaseIdOrderByEventTimeAsc(Long caseId);

    List<EvidenceVerificationEvent> findByArtifactVersionIdOrderByEventTimeAsc(Long artifactVersionId);
}
