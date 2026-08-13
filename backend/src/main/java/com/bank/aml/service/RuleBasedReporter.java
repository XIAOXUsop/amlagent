package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.tools.CorporateTool;
import com.bank.aml.tools.LegalSearchTool;
import com.bank.aml.tools.SanctionTool;
import com.bank.aml.tools.TransactionTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则兜底报告器：基于工具返回的真实数据按规则生成尽调报告。
 * <p>用途：
 * <ul>
 *   <li>LLM 报告缺失（Mock 降级 / 调用异常）时保证演示链路有可读输出；</li>
 *   <li>Phase 4 将在此基础上叠加独立 Guardrails 强制校验。</li>
 * </ul>
 */
@Component
public class RuleBasedReporter {

    private final TransactionTool transactionTool;
    private final CorporateTool corporateTool;
    private final SanctionTool sanctionTool;
    private final LegalSearchTool legalSearchTool;

    public RuleBasedReporter(TransactionTool transactionTool, CorporateTool corporateTool,
                             SanctionTool sanctionTool, LegalSearchTool legalSearchTool) {
        this.transactionTool = transactionTool;
        this.corporateTool = corporateTool;
        this.sanctionTool = sanctionTool;
        this.legalSearchTool = legalSearchTool;
    }

    public DueDiligenceReport generate(MockDataSource.Customer customer, String alertRule) {
        String tx = transactionTool.transactionProfile(customer.id());
        String corp = corporateTool.corporateProfile(customer.id());
        String sanction = sanctionTool.checkSanctions(customer.name(), customer.idCard());
        String legal = legalSearchTool.searchLegal("大额交易 客户尽职调查");

        List<String> riskPoints = new ArrayList<>();
        String riskLevel = assess(tx, sanction, riskPoints);

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
                List.of("交易数据源：Mock 核心交易系统", "工商股权：Mock 工商数据库", "黑名单：OFAC/国内制裁名单",
                        "法规：人民银行反洗钱规章"),
                false,
                List.of(),
                List.of()
        );
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
