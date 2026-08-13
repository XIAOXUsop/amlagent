package com.bank.aml.cost;

import com.bank.aml.cost.CostRouter.Complexity;
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
}
