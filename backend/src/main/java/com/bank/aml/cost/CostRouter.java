package com.bank.aml.cost;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 成本路由：根据预警规则复杂度分级，决定是否附加确定性报告流。
 * <p>预警规则来自请求侧，不能把其中的文字当作受信授权来跳过 Agent；
 * 在引入服务端签名的路由元数据前，所有业务工单都必须执行主 Agent。</p>
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

    /** RULE_ONLY 为兼容旧报告保留；当前生产路由不会返回该值。 */
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
     * 路由决策：当前始终执行主 Agent；复杂工单可附加确定性报告流。
     * <ul>
     *   <li>COMPLEX 且报告流启用 → AGENT_WITH_SUMMARY（不会产生第二次模型调用）；</li>
     *   <li>其余 → AGENT（仅主 Agent）。</li>
     * </ul>
     */
    public Route route(String alertRule, boolean ruleFallbackEnabled, boolean summaryEnabled) {
        Complexity complexity = assess(alertRule);
        if (summaryEnabled && complexity == Complexity.COMPLEX) {
            return Route.AGENT_WITH_SUMMARY;
        }
        return Route.AGENT;
    }
}
