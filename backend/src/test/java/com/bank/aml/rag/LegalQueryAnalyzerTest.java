package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegalQueryAnalyzerTest {
    private final LegalQueryAnalyzer analyzer = new LegalQueryAnalyzer();

    @Test
    void expandsBusinessSynonymsWithoutUsingAnLlm() {
        assertThat(analyzer.terms("公司账户一天累计划转200万元，多久报告"))
                .contains("非自然人客户", "当日累计", "报告时限", "200万元");
        assertThat(analyzer.terms("复杂企业的 UBO 如何识别"))
                .contains("受益所有人");
    }
}
