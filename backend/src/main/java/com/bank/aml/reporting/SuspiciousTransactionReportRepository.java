package com.bank.aml.reporting;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuspiciousTransactionReportRepository extends JpaRepository<SuspiciousTransactionReport, Long> {

    Optional<SuspiciousTransactionReport> findByCaseId(Long caseId);

    List<SuspiciousTransactionReport> findByStatusInOrderByCreatedAtAsc(
            Collection<SuspiciousTransactionReportStatus> statuses);

}
