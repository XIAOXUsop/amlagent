package com.bank.aml.workflow;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseExecutionRepository extends JpaRepository<CaseExecution, Long> {

    List<CaseExecution> findByCaseIdOrderByStartedAtAsc(Long caseId);

    List<CaseExecution> findByCaseIdAndExecutionVersionOrderByStartedAtAsc(Long caseId, int executionVersion);

}
