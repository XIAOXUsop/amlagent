package com.bank.aml.review;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnhancedDueDiligenceEvidenceRepository extends JpaRepository<EnhancedDueDiligenceEvidence, Long> {

    List<EnhancedDueDiligenceEvidence> findByRequestIdOrderByIdAsc(Long requestId);

}
