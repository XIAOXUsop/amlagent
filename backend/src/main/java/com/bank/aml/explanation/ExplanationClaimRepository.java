package com.bank.aml.explanation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 待验证事实 Claim 数据访问（v3 计划 §9）。 */
public interface ExplanationClaimRepository extends JpaRepository<ExplanationClaim, Long> {

    List<ExplanationClaim> findByCaseIdAndUnitIdOrderByIdAsc(Long caseId, Long unitId);

    List<ExplanationClaim> findByCaseIdOrderByIdAsc(Long caseId);

    Optional<ExplanationClaim> findByIdAndCaseId(Long id, Long caseId);

}
