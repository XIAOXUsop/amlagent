package com.bank.aml.explanation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplanationIssueRepository extends JpaRepository<ExplanationIssue, Long> {

    Optional<ExplanationIssue> findByCaseIdAndIssueKey(Long caseId, String issueKey);

    Optional<ExplanationIssue> findByIdAndCaseId(Long id, Long caseId);

    List<ExplanationIssue> findByCaseIdOrderByIdAsc(Long caseId);

    List<ExplanationIssue> findByCaseIdAndUnitIdOrderByIdAsc(Long caseId, Long unitId);

}
