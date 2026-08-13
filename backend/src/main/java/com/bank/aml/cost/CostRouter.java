package com.bank.aml.cost;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 成本路由：根据预警规则复杂度分级，决定是否需要调用 LLM。
 * <p>命中高风险特征关键词判 COMPLEX（需 LLM 深度推理）；否则 SIMPLE（可由规则引擎零成本处理）。
 * 这是 AI 应用成本控制的核心：能不用模型就不用模型。
 */
@Component
public class CostRouter {

    private static final List<String> HIGH_RISK_KEYWORDS = List.of(
            "制裁", "黑名单", "名单", "跨境", "夜间", "拆分", "现金", "UBO",
            "受益所有人", "快进快出", "分层", "可疑", "洗钱", "恐怖", "境外", "频繁"
    );

    public enum Complexity {
        SIMPLE, COMPLEX
    }

    /** 明确路由决策：RULE_ONLY（零 LLM）、AGENT（仅主 Agent）、AGENT_WITH_SUMMARY（主 Agent + 流式摘要） */
    public enum Route {
        RULE_ONLY, AGENT, AGENT_WITH_SUMMARY
    }

    public Complexity assess(String alertRule) {
        if (alertRule == null || alertRule.isBlank()) {
            return Complexity.SIMPLE;
        }
        for (String keyword : HIGH_RISK_KEYWORDS) {
            if (alertRule.contains(keyword)) {
                return Complexity.COMPLEX;
            }
        }
        return Complexity.SIMPLE;
    }

    /**
     * 路由决策：结合规则兜底开关与流式摘要开关。
     * <ul>
     *   <li>SIMPLE 且规则兜底启用 → RULE_ONLY（零 LLM 调用，含流式）；</li>
     *   <li>COMPLEX 且流式摘要启用 → AGENT_WITH_SUMMARY；</li>
     *   <li>其余 → AGENT（仅主 Agent）。</li>
     * </ul>
     */
    public Route route(String alertRule, boolean ruleFallbackEnabled, boolean summaryEnabled) {
        Complexity complexity = assess(alertRule);
        if (ruleFallbackEnabled && complexity == Complexity.SIMPLE) {
            return Route.RULE_ONLY;
        }
        if (summaryEnabled && complexity == Complexity.COMPLEX) {
            return Route.AGENT_WITH_SUMMARY;
        }
        return Route.AGENT;
    }
}
