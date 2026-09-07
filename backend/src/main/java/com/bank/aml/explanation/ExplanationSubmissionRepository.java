package com.bank.aml.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExplanationSubmissionRepository extends JpaRepository<ExplanationSubmission, Long> {

    Optional<ExplanationSubmission> findByIdAndCaseId(Long id, Long caseId);

    Optional<ExplanationSubmission> findByIdempotencyKey(String idempotencyKey);

    Optional<ExplanationSubmission> findTopByUnitIdOrderBySubmissionNoDesc(Long unitId);

    List<ExplanationSubmission> findByUnitIdOrderByIdAsc(Long unitId);

    List<ExplanationSubmission> findByCaseIdAndStateOrderByIdAsc(Long caseId, SubmissionState state);
}
