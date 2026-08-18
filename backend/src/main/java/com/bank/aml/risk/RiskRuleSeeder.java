package com.bank.aml.risk;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 风险规则种子数据：应用启动时初始化底线规则（幂等）。
 */
@Component
public class RiskRuleSeeder implements ApplicationRunner {

    private final RiskRuleRepository repository;
    private final RiskRuleEngine ruleEngine;

    public RiskRuleSeeder(RiskRuleRepository repository, RiskRuleEngine ruleEngine) {
        this.repository = repository;
        this.ruleEngine = ruleEngine;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedOrUpgrade("SANCTION_LEVEL_1", 2, "一级制裁名单命中（强制转人工）", 10,
                "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW",
                "命中 OFAC SDN 一级制裁名单，必须强制高风险并转人工复核");
        seedOrUpgrade("SANCTION_OTHER", 2, "其他制裁/可疑名单命中", 20,
                "sanction.maxSeverity >= 2", "高风险", "AUTO_DONE",
                "命中国内制裁或可疑交易名单，强制高风险");
        seedOrUpgrade("DATA_INCOMPLETE", 1, "交易数据不完整", 30,
                "transaction.dataComplete == false", "中风险", "MANUAL_REVIEW",
                "关键交易数据缺失时不能自动得出低风险结论，必须转人工补充核验");
        seedOrUpgrade("UBO_UNVERIFIED", 1, "受益所有人无法可靠核实", 40,
                "corporate.uboRiskSeverity >= 2", "高风险", "MANUAL_REVIEW",
                "最终受益所有人信息冲突或无法核实时，限制自动审批并转人工");
        seedOrUpgrade("TXN_PATTERN_HIGH", 1, "高严重度可疑交易模式", 50,
                "transaction.patternSeverity >= 2", "高风险", "AUTO_DONE",
                "拆分、分层或快速转移等组合模式达到高严重度，强制高风险");
        seedOrUpgrade("TXN_ABNORMAL", 2, "未解释的跨境+夜间高频异常", 60,
                "transaction.crossRatio > 20 && transaction.nightRatio > 30 && transaction.riskExplained == false",
                "高风险", "AUTO_DONE",
                "高频跨境与夜间交易叠加，风险特征显著");
        seedOrUpgrade("UBO_DOCUMENT_INCOMPLETE", 1, "受益所有人材料待更新", 70,
                "corporate.uboRiskSeverity == 1", "中风险", "AUTO_DONE",
                "现有资料可识别受益所有人但最新证明不完整，应补充材料并加强监测");
        seedOrUpgrade("TXN_MODERATE", 2, "需加强监测的交易模式", 80,
                "transaction.patternSeverity == 1", "中风险", "AUTO_DONE",
                "交易模式发生变化但尚未达到高风险模式阈值，需加强监测");
        seedOrUpgrade("CROSS_BORDER_MODERATE", 1, "未解释的显著跨境交易", 90,
                "transaction.crossRatio >= 10 && transaction.riskExplained == false", "中风险", "AUTO_DONE",
                "显著跨境活动缺少可信业务解释，需加强监测");
        seedOrUpgrade("NIGHT_ACTIVITY_MODERATE", 1, "未解释的显著夜间交易", 100,
                "transaction.nightRatio >= 20 && transaction.riskExplained == false", "中风险", "AUTO_DONE",
                "显著夜间活动与客户经营模式不一致，需加强监测");

        // 对人工新增或修改的启用规则同样做启动校验，避免无效 DSL 静默运行。
        repository.findByEnabledTrueOrderByPriorityAsc()
                .forEach(rule -> ruleEngine.validateExpression(rule.getConditionExpression()));
        // 种子完成后立即刷新规则缓存，避免首个 Guardrails 评估读到空快照
        ruleEngine.invalidateCache();
    }

    private void seedOrUpgrade(String code, int version, String name, int priority, String expr,
                               String target, String action, String desc) {
        ruleEngine.validateExpression(expr);
        Optional<RiskRule> existing = repository.findByRuleCode(code);
        if (existing.isPresent() && existing.get().getVersion() >= version) {
            return;
        }
        RiskRule rule = existing.orElseGet(RiskRule::new);
        rule.setRuleCode(code);
        rule.setVersion(version);
        rule.setRuleName(name);
        rule.setPriority(priority);
        rule.setConditionExpression(expr);
        rule.setTargetRiskLevel(target);
        rule.setAction(action);
        rule.setDescription(desc);
        rule.setEnabled(true);
        repository.save(rule);
    }
}
