package com.bank.aml.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalReportRepository extends JpaRepository<EvalReportEntity, Long> {

    List<EvalReportEntity> findByEvalTypeOrderByCreatedAtDesc(String evalType);
}
