package com.bank.aml.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Agent 风险评测集生成器：程序化生成约 100 条合成案例（固定种子，可复现）。
 * 覆盖正常 / 跨境夜间 / 拆分交易 / 制裁命中 / 复杂 UBO / 数据缺失与异常等场景。
 */
@Component
public class CaseSetGenerator {

    public record AgentEvalCase(
            String id, String scenario,
            double crossRatio, double nightRatio, long largeCount,
            int maxSeverity, boolean sanctionHit,
            String expectedRiskLevel, boolean expectEscalate
    ) {
    }

    private static final long SEED = 20260812L;

    public List<AgentEvalCase> generate() {
        Random rand = new Random(SEED);
        List<AgentEvalCase> cases = new ArrayList<>();
        int seq = 1;

        // 正常客户：跨境/夜间占比极低，无制裁
        for (int i = 0; i < 25; i++) {
            double cross = rand.nextInt(4);
            double night = rand.nextInt(8);
            long large = rand.nextInt(2);
            cases.add(evCase(seq++, "NORMAL", cross, night, large, 0, false));
        }
        // 跨境+夜间高频异常
        for (int i = 0; i < 20; i++) {
            double cross = 25 + rand.nextInt(20);
            double night = 35 + rand.nextInt(20);
            long large = rand.nextInt(9);
            cases.add(evCase(seq++, "CROSS_NIGHT", cross, night, large, 0, false));
        }
        // 拆分交易：大额笔数多但跨境/夜间一般
        for (int i = 0; i < 15; i++) {
            double cross = rand.nextInt(6);
            double night = 5 + rand.nextInt(15);
            long large = 3 + rand.nextInt(13);
            cases.add(evCase(seq++, "SPLIT", cross, night, large, 0, false));
        }
        // 制裁名单命中（含一级）
        for (int i = 0; i < 15; i++) {
            double cross = rand.nextInt(30);
            double night = rand.nextInt(30);
            long large = rand.nextInt(5);
            int sev = i % 3 == 0 ? 1 : 2; // 约 1/3 为一级制裁
            cases.add(evCase(seq++, "SANCTION", cross, night, large, sev, true));
        }
        // 复杂 UBO / 关联交易（跨境夜间中等）
        for (int i = 0; i < 15; i++) {
            double cross = 10 + rand.nextInt(20);
            double night = 10 + rand.nextInt(25);
            long large = rand.nextInt(6);
            cases.add(evCase(seq++, "UBO", cross, night, large, 0, false));
        }
        // 数据缺失与工具异常：交易特征缺失，评级偏低但需标记复核
        for (int i = 0; i < 10; i++) {
            double cross = 0;
            double night = rand.nextInt(5);
            long large = 0;
            cases.add(evCase(seq++, "DATA_MISSING", cross, night, large, 0, false));
        }
        return cases;
    }

    /** 依据规则语义推导预期结果（确定性） */
    private AgentEvalCase evCase(int seq, String scenario, double cross, double night,
                                 long large, int maxSeverity, boolean sanctionHit) {
        String expected;
        boolean escalate = false;
        if (maxSeverity == 1) {
            // 仅一级制裁名单强制转人工（与其他制裁名单区分）
            expected = "高风险";
            escalate = true;
        } else if (sanctionHit) {
            expected = "高风险";
        } else if (cross > 20 && night > 30) {
            expected = "高风险";
        } else if (cross > 0 || night > 0 || large > 0) {
            expected = "中风险";
        } else {
            expected = "低风险";
        }
        return new AgentEvalCase("EV-" + seq, scenario, cross, night, large,
                maxSeverity, sanctionHit, expected, escalate);
    }
}
