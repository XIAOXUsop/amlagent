package com.bank.aml.explanation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationBasisRepository extends JpaRepository<VerificationBasis, Long> {

    Optional<VerificationBasis> findTopByCaseIdOrderByBasisRevisionDesc(Long caseId);
}
