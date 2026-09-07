package com.bank.aml.investigation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AlertInvestigationCoverageRepository
        extends JpaRepository<AlertInvestigationCoverage, Long> {
    List<AlertInvestigationCoverage> findByCaseIdOrderByAlertIdAsc(Long caseId);
    List<AlertInvestigationCoverage> findByCaseIdInOrderByAlertIdAsc(java.util.Collection<Long> caseIds);
    Optional<AlertInvestigationCoverage> findByAlertId(Long alertId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AlertInvestigationCoverage c WHERE c.alertId = :alertId AND c.caseId = :caseId")
    Optional<AlertInvestigationCoverage> findByAlertIdAndCaseIdForUpdate(@Param("alertId") Long alertId,
                                                                         @Param("caseId") Long caseId);
}
