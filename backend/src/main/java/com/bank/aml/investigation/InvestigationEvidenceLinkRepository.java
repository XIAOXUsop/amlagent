package com.bank.aml.investigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestigationEvidenceLinkRepository extends JpaRepository<InvestigationEvidenceLink, Long> {
    List<InvestigationEvidenceLink> findByCaseIdOrderByCreatedAtAsc(Long caseId);
    List<InvestigationEvidenceLink> findByCaseIdIn(java.util.Collection<Long> caseIds);
    List<InvestigationEvidenceLink> findByHypothesisIdOrderByCreatedAtAsc(Long hypothesisId);
    boolean existsByCaseId(Long caseId);
    boolean existsByHypothesisIdAndStance(Long hypothesisId, EvidenceStance stance);
}
