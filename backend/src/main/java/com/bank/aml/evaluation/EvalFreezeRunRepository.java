package com.bank.aml.evaluation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalFreezeRunRepository extends JpaRepository<EvalFreezeRun, Long> {

    Optional<EvalFreezeRun> findByFreezeId(String freezeId);

}
