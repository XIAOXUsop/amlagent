package com.bank.aml.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExplanationIssueRepository extends JpaRepository<ExplanationIssue, Long> {

    Optional<ExplanationIssue> findByCaseIdAndIssueKey(Long caseId, String issueKey);

    Optional<ExplanationIssue> findByIdAndCaseId(Long id, Long caseId);

    List<ExplanationIssue> findByCaseIdOrderByIdAsc(Long caseId);

    List<ExplanationIssue> findByCaseIdAndUnitIdOrderByIdAsc(Long caseId, Long unitId);
}
