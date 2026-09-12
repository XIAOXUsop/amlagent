package com.bank.aml.sanction;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SanctionCandidateReviewRepository extends JpaRepository<SanctionCandidateReview, Long> {

    List<SanctionCandidateReview> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    Optional<SanctionCandidateReview> findTopByCustomerIdAndCandidateFingerprintOrderByReviewRevisionDesc(
            String customerId, String candidateFingerprint);

}
