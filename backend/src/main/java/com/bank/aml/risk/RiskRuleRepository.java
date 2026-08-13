package com.bank.aml.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {

    List<RiskRule> findByEnabledTrueOrderByPriorityAsc();

    Optional<RiskRule> findByRuleCode(String ruleCode);
}
