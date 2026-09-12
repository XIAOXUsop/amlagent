package com.bank.aml.review;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualReviewRepository extends JpaRepository<ManualReview, Long> {

    List<ManualReview> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    List<ManualReview> findAllByOrderByCreatedAtDesc();

}
