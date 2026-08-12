package com.bank.aml.risk;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 风险规则种子数据：应用启动时初始化底线规则（幂等）。
 */
@Component
public class RiskRuleSeeder implements ApplicationRunner {

    private final RiskRuleRepository repository;

    public RiskRuleSeeder(RiskRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("SANCTION_LEVEL_1", "一级制裁名单命中（强制转人工）", 100,
                "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW",
                "命中 OFAC SDN 一级制裁名单，必须强制高风险并转人工复核");
        seed("SANCTION_OTHER", "其他制裁/可疑名单命中", 90,
                "sanction.sanctionHit == true", "高风险", "AUTO_DONE",
                "命中国内制裁或可疑交易名单，强制高风险");
        seed("TXN_ABNORMAL", "跨境+夜间高频异常", 80,
                "transaction.crossRatio > 20 && transaction.nightRatio > 30", "高风险", "AUTO_DONE",
                "高频跨境与夜间交易叠加，风险特征显著");
        seed("TXN_MODERATE", "存在跨境或夜间交易", 70,
                "transaction.crossRatio > 0 || transaction.nightRatio > 0", "中风险", "AUTO_DONE",
                "存在少量跨境/夜间交易，需加强监测");
    }

    private void seed(String code, String name, int priority, String expr,
                      String target, String action, String desc) {
        if (repository.existsByRuleCode(code)) {
            return;
        }
        RiskRule r = new RiskRule();
        r.setRuleCode(code);
        r.setRuleName(name);
        r.setPriority(priority);
        r.setConditionExpression(expr);
        r.setTargetRiskLevel(target);
        r.setAction(action);
        r.setDescription(desc);
        r.setEnabled(true);
        repository.save(r);
    }
}
