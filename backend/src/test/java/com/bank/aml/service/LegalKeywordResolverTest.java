package com.bank.aml.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegalKeywordResolverTest {

    private final LegalKeywordResolver resolver = new LegalKeywordResolver();

    @Test
    void resolvesCrossBorderAndNightKeywords() {
        assertThat(resolver.resolve("大额频繁跨国转账、夜间集中交易"))
                .contains("跨境", "夜间");
    }

    @Test
    void resolvesSplitAndCashKeywords() {
        assertThat(resolver.resolve("现金频繁存取、涉嫌拆分交易"))
                .contains("现金", "拆分", "可疑交易");
    }

    @Test
    void resolvesSanctionKeyword() {
        assertThat(resolver.resolve("命中制裁名单")).contains("制裁", "名单");
    }

    @Test
    void fallsBackToDefaultKeywordsWhenNoMatch() {
        assertThat(resolver.resolve("常规监测")).contains("尽职调查", "风险评估");
    }

    @Test
    void returnsKeywordsForBlankRule() {
        assertThat(resolver.resolve("")).isNotEmpty();
    }
}
