package com.bank.aml.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 风险规则回归案例生成器：使用固定种子生成 100 条确定性合成特征，
 * 用于验证风险规则的边界、负例和人工复核策略。
 *
 * <p>每类场景的期望结果都在案例定义处显式标注，不从待测规则反推，
 * 避免“实现和标准答案犯同一个错误”的伪回归测试。
 */
@Component
public class RuleRegressionCaseGenerator {

    public record RuleRegressionCase(
            String id,
            String scenario,
            double crossRatio,
            double nightRatio,
            long largeCount,
            int maxSeverity,
            boolean sanctionHit,
            boolean transactionDataComplete,
            boolean transactionRiskExplained,
            int transactionPatternSeverity,
            int uboRiskSeverity,
            String expectedRiskLevel,
            boolean expectManualReview
    ) {
    }

    private static final long SEED = 20260812L;

    public List<RuleRegressionCase> generate() {
        Random rand = new Random(SEED);
        List<RuleRegressionCase> cases = new ArrayList<>();
        int seq = 1;

        // 普通低风险：即使存在少量跨境、夜间或大额交易，也不应机械升级。
        for (int i = 0; i < 10; i++) {
            cases.add(regressionCase(seq++, "NORMAL",
                    rand.nextInt(4), rand.nextInt(8), rand.nextInt(2),
                    0, false, true, false, 0, 0,
                    "低风险", false));
        }

        // 合法跨境负例：跨境比例很高，但已有可信合同、物流或业务材料解释。
        for (int i = 0; i < 10; i++) {
            cases.add(regressionCase(seq++, "LEGITIMATE_CROSS_BORDER",
                    30 + rand.nextInt(31), rand.nextInt(10), rand.nextInt(4),
                    0, false, true, true, 0, 0,
                    "低风险", false));
        }

        // 合法夜间负例：夜间比例很高，但符合客户时区和经营模式。
        for (int i = 0; i < 10; i++) {
            cases.add(regressionCase(seq++, "LEGITIMATE_NIGHT_ACTIVITY",
                    rand.nextInt(8), 40 + rand.nextInt(41), rand.nextInt(3),
                    0, false, true, true, 0, 0,
                    "低风险", false));
        }

        // 未解释的高跨境 + 高夜间组合异常。
        for (int i = 0; i < 15; i++) {
            cases.add(regressionCase(seq++, "CROSS_NIGHT_HIGH",
                    25 + rand.nextInt(20), 35 + rand.nextInt(20), rand.nextInt(9),
                    0, false, true, false, 0, 0,
                    "高风险", false));
        }

        // 拆分、分层或快速转移等高严重度组合模式。
        for (int i = 0; i < 15; i++) {
            cases.add(regressionCase(seq++, i % 2 == 0 ? "STRUCTURING_PATTERN" : "LAYERING_PATTERN",
                    rand.nextInt(8), 5 + rand.nextInt(14), 3 + rand.nextInt(13),
                    0, false, true, false, 2, 0,
                    "高风险", false));
        }

        // 制裁名单命中：一级制裁必须人工复核，二级命中强制高风险但可继续自动流程。
        for (int i = 0; i < 15; i++) {
            int severity = i % 3 == 0 ? 1 : 2;
            cases.add(regressionCase(seq++, "SANCTION",
                    rand.nextInt(10), rand.nextInt(15), rand.nextInt(5),
                    severity, true, true, false, 0, 0,
                    "高风险", severity == 1));
        }

        // 受益所有人无法可靠核实：强制高风险并转人工。
        for (int i = 0; i < 8; i++) {
            cases.add(regressionCase(seq++, "UBO_UNVERIFIED",
                    rand.nextInt(10), rand.nextInt(15), rand.nextInt(6),
                    0, false, true, false, 0, 2,
                    "高风险", true));
        }

        // 数据缺失：不能把“未发现异常”当作低风险证据，至少中风险并转人工补充核验。
        for (int i = 0; i < 7; i++) {
            cases.add(regressionCase(seq++, "DATA_MISSING",
                    0, rand.nextInt(5), 0,
                    0, false, false, false, 0, 0,
                    "中风险", true));
        }

        // 尚未达到高风险阈值的行为变化，应升级为中风险并加强监测。
        for (int i = 0; i < 5; i++) {
            cases.add(regressionCase(seq++, "PATTERN_MODERATE",
                    rand.nextInt(8), rand.nextInt(15), rand.nextInt(4),
                    0, false, true, false, 1, 0,
                    "中风险", false));
        }

        // 单一聚合特征达到显著阈值且无可信解释，应升级为中风险。
        for (int i = 0; i < 3; i++) {
            cases.add(regressionCase(seq++, "UNEXPLAINED_CROSS_BORDER",
                    10 + rand.nextInt(11), rand.nextInt(10), rand.nextInt(3),
                    0, false, true, false, 0, 0,
                    "中风险", false));
        }
        for (int i = 0; i < 2; i++) {
            cases.add(regressionCase(seq++, "UNEXPLAINED_NIGHT_ACTIVITY",
                    rand.nextInt(8), 20 + rand.nextInt(11), rand.nextInt(3),
                    0, false, true, false, 0, 0,
                    "中风险", false));
        }

        return List.copyOf(cases);
    }

    private RuleRegressionCase regressionCase(
            int seq,
            String scenario,
            double crossRatio,
            double nightRatio,
            long largeCount,
            int maxSeverity,
            boolean sanctionHit,
            boolean transactionDataComplete,
            boolean transactionRiskExplained,
            int transactionPatternSeverity,
            int uboRiskSeverity,
            String expectedRiskLevel,
            boolean expectManualReview
    ) {
        return new RuleRegressionCase(
                "RULE-%03d".formatted(seq),
                scenario,
                crossRatio,
                nightRatio,
                largeCount,
                maxSeverity,
                sanctionHit,
                transactionDataComplete,
                transactionRiskExplained,
                transactionPatternSeverity,
                uboRiskSeverity,
                expectedRiskLevel,
                expectManualReview);
    }
}
