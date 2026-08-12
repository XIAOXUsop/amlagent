package com.bank.aml.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseExecutionRepository extends JpaRepository<CaseExecution, Long> {

    List<CaseExecution> findByCaseIdOrderByStartedAtAsc(Long caseId);

    List<CaseExecution> findByCaseIdAndExecutionVersionOrderByStartedAtAsc(Long caseId, int executionVersion);
}
