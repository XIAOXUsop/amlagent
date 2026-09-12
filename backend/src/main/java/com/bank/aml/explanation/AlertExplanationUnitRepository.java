package com.bank.aml.explanation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertExplanationUnitRepository extends JpaRepository<AlertExplanationUnit, Long> {

    Optional<AlertExplanationUnit> findByIdAndCaseId(Long id, Long caseId);

    Optional<AlertExplanationUnit> findByCaseIdAndAlertId(Long caseId, Long alertId);

    List<AlertExplanationUnit> findByCaseIdOrderByIdAsc(Long caseId);

    List<AlertExplanationUnit> findByCaseIdAndHypothesisIdOrderByIdAsc(Long caseId, Long hypothesisId);

}
