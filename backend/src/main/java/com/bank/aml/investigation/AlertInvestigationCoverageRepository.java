package com.bank.aml.investigation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertInvestigationCoverageRepository extends JpaRepository<AlertInvestigationCoverage, Long> {

    List<AlertInvestigationCoverage> findByCaseIdOrderByAlertIdAsc(Long caseId);

    List<AlertInvestigationCoverage> findByCaseIdInOrderByAlertIdAsc(Collection<Long> caseIds);

    Optional<AlertInvestigationCoverage> findByAlertId(Long alertId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AlertInvestigationCoverage c WHERE c.alertId = :alertId AND c.caseId = :caseId")
    Optional<AlertInvestigationCoverage> findByAlertIdAndCaseIdForUpdate(@Param("alertId") Long alertId,
            @Param("caseId") Long caseId);

}
