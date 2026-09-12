package com.bank.aml.investigation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationEvidenceLinkRepository extends JpaRepository<InvestigationEvidenceLink, Long> {

    List<InvestigationEvidenceLink> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    List<InvestigationEvidenceLink> findByCaseIdIn(Collection<Long> caseIds);

    List<InvestigationEvidenceLink> findByHypothesisIdOrderByCreatedAtAsc(Long hypothesisId);

    boolean existsByCaseId(Long caseId);

    boolean existsByHypothesisIdAndStance(Long hypothesisId, EvidenceStance stance);

}
