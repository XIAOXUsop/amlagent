package com.bank.aml.investigation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestigationHypothesisRepository extends JpaRepository<InvestigationHypothesis, Long> {

    List<InvestigationHypothesis> findByCaseIdOrderByIdAsc(Long caseId);

    List<InvestigationHypothesis> findByCaseIdInOrderByIdAsc(Collection<Long> caseIds);

    Optional<InvestigationHypothesis> findByCaseIdAndHypothesisCode(Long caseId, String hypothesisCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM InvestigationHypothesis h WHERE h.id = :id AND h.caseId = :caseId")
    Optional<InvestigationHypothesis> findByIdAndCaseIdForUpdate(@Param("id") Long id, @Param("caseId") Long caseId);

}
