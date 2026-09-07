package com.bank.aml.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnhancedDueDiligenceEvidenceRepository
        extends JpaRepository<EnhancedDueDiligenceEvidence, Long> {

    List<EnhancedDueDiligenceEvidence> findByRequestIdOrderByIdAsc(Long requestId);
}
