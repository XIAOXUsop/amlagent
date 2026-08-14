package com.bank.aml.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EvalFreezeRunRepository extends JpaRepository<EvalFreezeRun, Long> {

    Optional<EvalFreezeRun> findByFreezeId(String freezeId);
}
