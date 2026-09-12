package com.bank.aml.evaluation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalReportRepository extends JpaRepository<EvalReportEntity, Long> {

    List<EvalReportEntity> findByEvalTypeOrderByCreatedAtDesc(String evalType);

}
