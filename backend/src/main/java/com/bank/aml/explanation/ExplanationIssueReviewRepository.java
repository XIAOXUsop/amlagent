package com.bank.aml.explanation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 问题降级提案数据访问（验收 A5-04）。 */
public interface ExplanationIssueReviewRepository extends JpaRepository<ExplanationIssueReview, Long> {

    Optional<ExplanationIssueReview> findByIdAndCaseId(Long id, Long caseId);

    List<ExplanationIssueReview> findByCaseIdAndStatusOrderByIdAsc(Long caseId, String status);

    List<ExplanationIssueReview> findByIssueIdOrderByProposedAtAsc(Long issueId);

}
