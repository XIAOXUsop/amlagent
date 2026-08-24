package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.tools.CorporateTool;
import com.bank.aml.tools.LegalSearchTool;
import com.bank.aml.tools.SanctionTool;
import com.bank.aml.tools.TransactionTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则兜底报告器：基于冻结快照按规则生成尽调报告。
 * <p>用途：
 * <ul>
 *   <li>LLM 报告缺失（Mock 降级 / 调用异常）时保证演示链路有可读输出；</li>
 *   <li>Phase 4 将在此基础上叠加独立 Guardrails 强制校验。</li>
 * </ul>
 * <p>注意：只读 {@link InvestigationSnapshot}（与 Agent/Guardrails 共享同一份冻结事实），
 * 不直接访问可变数据源与实时 RAG 索引，避免规则降级报告与模型报告之间的数据漂移。
 */
@Component
public class RuleBasedReporter {

    public DueDiligenceReport generate(InvestigationSnapshot snapshot, String alertRule) {
        var customer = snapshot.customer();
        String tx = TransactionTool.format(snapshot.transactions(), customer.id());
        String corp = CorporateTool.format(snapshot.shareholdings(), customer.id());
        String sanction = SanctionTool.format(snapshot.sanctionHits());
        String legal = LegalSearchTool.format(snapshot.legalEvidence());

        List<String> riskPoints = new ArrayList<>();
        String riskLevel = assess(tx, sanction, riskPoints);
        List<String> findingCodes = findings(snapshot, riskLevel);
        List<String> actionCodes = actions(riskLevel);
        List<String> evidenceChain = new ArrayList<>(List.of(
                "交易数据源：冻结快照", "工商股权：冻结快照", "黑名单：后端制裁筛查"));
        snapshot.legalEvidence().stream().map(doc -> "法规证据：" + doc.evidenceId()).forEach(evidenceChain::add);

        return new DueDiligenceReport(
                customer.id(),
                customer.name(),
                riskLevel,
                tx,
                corp,
                isHit(sanction) ? List.of(sanction) : List.of(),
                List.of(legal),
                riskPoints,
                conclusion(riskLevel),
                evidenceChain,
                false,
                findingCodes,
                actionCodes
        );
    }

    /** 模型返回了不可安全接受的结构化输出时，生成只用于人工复核的保守报告。 */
    public DueDiligenceReport generateSafetyHold(InvestigationSnapshot snapshot, String alertRule,
                                                 List<String> validationCodes) {
        DueDiligenceReport base = generate(snapshot, alertRule);
        List<String> points = new ArrayList<>(base.riskPoints());
        points.add("模型输出未通过生产契约校验：" + String.join("、", validationCodes));
        SetBuilder findings = new SetBuilder(base.findingCodes());
        findings.remove("NORMAL_TRANSACTION_PATTERN");
        findings.add("RISK_ASSESSMENT_UNCERTAIN");
        SetBuilder actions = new SetBuilder(base.actionCodes());
        actions.add("MANUAL_REVIEW");
        actions.add("ENHANCED_DUE_DILIGENCE");
        String risk = "低风险".equals(base.riskLevel()) ? "中风险" : base.riskLevel();
        return new DueDiligenceReport(base.customerId(), base.customerName(), risk,
                base.transactionProfile(), base.corporateProfile(), base.sanctions(), base.legalBasis(), points,
                "模型输出未通过生产契约校验，已生成保守报告并强制转人工复核。",
                base.evidenceChain(), true, findings.values(), actions.values());
    }

    private List<String> findings(InvestigationSnapshot snapshot, String riskLevel) {
        SetBuilder findings = new SetBuilder(List.of());
        var facts = snapshot.riskFacts();
        if (facts.sanctionHit()) {
            if (facts.maxSeverity() == 1) findings.add("SANCTION_LEVEL_1_MATCH");
            else if (facts.maxSeverity() >= 2) findings.add("DOMESTIC_WATCHLIST_MATCH");
            else findings.add("RISK_ASSESSMENT_UNCERTAIN");
        } else {
            findings.add("NO_SANCTION_HIT");
        }
        if (!facts.transactionDataComplete()) {
            findings.add("TRANSACTION_DATA_UNAVAILABLE");
            findings.add("RISK_ASSESSMENT_UNCERTAIN");
        }
        if (facts.crossRatio() > 0) findings.add("CROSS_BORDER_ACTIVITY");
        if (facts.uboRiskSeverity() >= 2) findings.add("UBO_UNVERIFIED");
        else if (facts.uboRiskSeverity() == 1) findings.add("UBO_DOCUMENTS_INCOMPLETE");
        if ("低风险".equals(riskLevel) && findings.values().stream().noneMatch("NORMAL_TRANSACTION_PATTERN"::equals)) {
            findings.add("NORMAL_TRANSACTION_PATTERN");
        }
        return findings.values();
    }

    private List<String> actions(String riskLevel) {
        return switch (riskLevel) {
            case "高风险" -> List.of("ENHANCED_DUE_DILIGENCE");
            case "中风险" -> List.of("INCREASE_MONITORING");
            default -> List.of("MAINTAIN_STANDARD_MONITORING");
        };
    }

    private static final class SetBuilder {
        private final LinkedHashSet<String> values;

        private SetBuilder(List<String> initial) {
            this.values = new LinkedHashSet<>(initial == null ? List.of() : initial);
        }

        private void add(String value) {
            values.add(value);
        }

        private void remove(String value) {
            values.remove(value);
        }

        private List<String> values() {
            return List.copyOf(values);
        }
    }

    /** 工具文本为"黑名单命中结果："开头时视为命中（避免误匹配"未命中"） */
    private boolean isHit(String sanction) {
        return sanction != null && sanction.startsWith("黑名单命中结果");
    }

    /** 规则评级：制裁命中 > 交易异常 > 正常；一级制裁的强制转人工由 Guardrails 阶段负责 */
    private String assess(String tx, String sanction, List<String> riskPoints) {
        if (isHit(sanction)) {
            riskPoints.add("命中制裁 / 可疑交易名单：" + sanction.split("\n")[0].replace("- ", ""));
            return "高风险";
        }
        double nightRatio = extractRatio(tx, "夜间交易");
        double crossRatio = extractRatio(tx, "跨境交易");
        if (crossRatio > 20 && nightRatio > 30) {
            riskPoints.add("存在高频夜间交易与跨境交易叠加特征，与交易规模不相匹配");
            return "中风险";
        }
        if (crossRatio > 0 || nightRatio > 0) {
            riskPoints.add("存在少量跨境 / 夜间交易，需结合尽职调查进一步核实");
            return "中风险";
        }
        riskPoints.add("交易结构与客户身份相符，未发现明显洗钱风险特征");
        return "低风险";
    }

    /** 从工具文本提取"xxx：N 笔，占比 X%"中的百分比 */
    private double extractRatio(String text, String label) {
        Matcher m = Pattern.compile(Pattern.quote(label) + ".*?占比\\s*(\\d+(?:\\.\\d+)?)%").matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private String conclusion(String riskLevel) {
        return switch (riskLevel) {
            case "高风险" -> "建议立即转人工深度尽调，视情况采取客户风险等级上调、限制交易、上报可疑交易报告等措施。";
            case "中风险" -> "建议加强交易监测，补充收集客户身份与资金来源信息后复审。";
            default -> "建议维持现有监测频率，按常规开展后续回访。";
        };
    }
}
