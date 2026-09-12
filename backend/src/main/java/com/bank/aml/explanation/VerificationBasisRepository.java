package com.bank.aml.explanation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationBasisRepository extends JpaRepository<VerificationBasis, Long> {

    Optional<VerificationBasis> findTopByCaseIdOrderByBasisRevisionDesc(Long caseId);

}
