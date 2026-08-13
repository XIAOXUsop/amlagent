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
}
