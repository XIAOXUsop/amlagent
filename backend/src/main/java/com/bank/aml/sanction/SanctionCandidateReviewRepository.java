package com.bank.aml.sanction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SanctionCandidateReviewRepository extends JpaRepository<SanctionCandidateReview, Long> {
    List<SanctionCandidateReview> findByCustomerIdOrderByCreatedAtAsc(String customerId);
    Optional<SanctionCandidateReview> findTopByCustomerIdAndCandidateFingerprintOrderByReviewRevisionDesc(
            String customerId, String candidateFingerprint);
}
