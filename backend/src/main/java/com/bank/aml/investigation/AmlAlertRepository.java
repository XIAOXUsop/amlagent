package com.bank.aml.investigation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AmlAlertRepository extends JpaRepository<AmlAlert, Long> {

    boolean existsByExternalAlertId(String externalAlertId);

    List<AmlAlert> findByStatusOrderByOccurredAtAsc(AlertStatus status);

    List<AmlAlert> findByCaseIdOrderByOccurredAtAsc(Long caseId);

    List<AmlAlert> findByCustomerIdAndStatusOrderByOccurredAtAsc(String customerId, AlertStatus status);

    long countByCaseIdAndStatus(Long caseId, AlertStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AmlAlert a WHERE a.id = :id")
    Optional<AmlAlert> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AmlAlert a WHERE a.caseId = :caseId AND a.status IN :statuses ORDER BY a.id")
    List<AmlAlert> findByCaseIdAndStatusInForUpdate(@Param("caseId") Long caseId,
            @Param("statuses") Collection<AlertStatus> statuses);

}
