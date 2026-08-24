package com.bank.aml.cost;

import com.bank.aml.cost.CostRouter.Complexity;
import com.bank.aml.cost.CostRouter.Route;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CostRouterTest {

    private final CostRouter router = new CostRouter();

    @Test
    void highRiskKeywordsRouteToComplex() {
        assertThat(router.assess("大额频繁跨国转账、夜间集中交易")).isEqualTo(Complexity.COMPLEX);
        assertThat(router.assess("命中制裁名单")).isEqualTo(Complexity.COMPLEX);
        assertThat(router.assess("现金拆分存取")).isEqualTo(Complexity.COMPLEX);
        assertThat(router.assess("复杂 UBO 股权穿透")).isEqualTo(Complexity.COMPLEX);
    }

    @Test
    void simpleRulesRouteToSimple() {
        assertThat(router.assess("常规监测")).isEqualTo(Complexity.SIMPLE);
        assertThat(router.assess("")).isEqualTo(Complexity.SIMPLE);
        assertThat(router.assess(null)).isEqualTo(Complexity.SIMPLE);
    }

    @Test
    void naturalLanguageNeverSilentlyDowngradesToRuleOnly() {
        assertThat(router.route("常规监测", true, false)).isEqualTo(Route.AGENT);
        assertThat(router.route("常规监测", true, true)).isEqualTo(Route.AGENT);
    }

    @Test
    void requestControlledPrefixCannotBypassAgent() {
        assertThat(router.route("[RULE_ONLY_VERIFIED]常规监测", true, false)).isEqualTo(Route.AGENT);
        assertThat(router.route("[RULE_ONLY_VERIFIED]常规监测", false, false)).isEqualTo(Route.AGENT);
    }

    @Test
    void simpleWithoutRuleFallbackStillCallsAgent() {
        // SIMPLE 但规则兜底关闭 → 仍走主 Agent（保留 LLM 推理能力）
        assertThat(router.route("常规监测", false, false)).isEqualTo(Route.AGENT);
    }

    @Test
    void complexRoutesBySummarySwitch() {
        // COMPLEX + 摘要关闭 → 仅主 Agent 一次
        assertThat(router.route("命中制裁名单", true, false)).isEqualTo(Route.AGENT);
        // COMPLEX + 报告流开启 → 主 Agent + 已落库结果的确定性流，不产生第二次模型调用
        assertThat(router.route("命中制裁名单", true, true)).isEqualTo(Route.AGENT_WITH_SUMMARY);
    }
}
