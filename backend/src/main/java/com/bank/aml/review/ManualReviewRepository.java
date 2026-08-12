package com.bank.aml.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManualReviewRepository extends JpaRepository<ManualReview, Long> {

    List<ManualReview> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    List<ManualReview> findAllByOrderByCreatedAtDesc();
}
