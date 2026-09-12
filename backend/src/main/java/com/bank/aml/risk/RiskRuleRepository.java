package com.bank.aml.risk;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {

    List<RiskRule> findByEnabledTrueOrderByPriorityAsc();

    Optional<RiskRule> findByRuleCode(String ruleCode);

}
