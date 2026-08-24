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

    @Test
    void parsesAuthorityFieldsAndIntentDeterministically() {
        LegalQueryAnalyzer.ParsedQuery parsed =
                analyzer.parse("中国人民银行令〔2016〕第3号 第三条 大额交易 200万元 应当报告");

        assertThat(parsed.docNumbers()).contains("中国人民银行令〔2016〕第3号");
        assertThat(parsed.articleNumbers()).contains("第三条");
        assertThat(parsed.amounts()).contains("200万元");
        assertThat(parsed.durations()).isEmpty();
        assertThat(parsed.actions()).contains("应当", "报告");
        assertThat(parsed.intent()).isEqualTo(LegalQueryAnalyzer.QueryIntent.REGULATION_FACT);
    }

    @Test
    void classifiesHighRiskDisposalAndGeneralKnowledgeIntents() {
        assertThat(analyzer.parse("客户命中恐怖活动名单是否应当立即冻结？").intent())
                .isEqualTo(LegalQueryAnalyzer.QueryIntent.HIGH_RISK_DISPOSAL);
        assertThat(analyzer.parse("什么是洗钱").intent())
                .isEqualTo(LegalQueryAnalyzer.QueryIntent.GENERAL_KNOWLEDGE);
        assertThat(analyzer.parse("客户身份资料至少保存几年").intent())
                .isEqualTo(LegalQueryAnalyzer.QueryIntent.REGULATION_FACT);
        assertThat(analyzer.parse("冻结措施能提前告知被命中的客户吗？").intent())
                .isEqualTo(LegalQueryAnalyzer.QueryIntent.REGULATION_FACT);
        assertThat(analyzer.parse("客户命中名单后能否等待人工审批再冻结？").intent())
                .isEqualTo(LegalQueryAnalyzer.QueryIntent.HIGH_RISK_DISPOSAL);
    }

    @Test
    void rejectsQuestionsWithoutAnyAmlLegalDomainSignal() {
        assertThat(analyzer.isAmlLegalDomain("员工连续加班应支付多少工资")).isFalse();
        assertThat(analyzer.isAmlLegalDomain("明天上海会下雨吗")).isFalse();
        assertThat(analyzer.isAmlLegalDomain("银行理财产品净值保本吗")).isFalse();
        assertThat(analyzer.isAmlLegalDomain("信用卡逾期利息率由谁规定")).isFalse();
        assertThat(analyzer.isAmlLegalDomain("银行客户身份资料应保存几年")).isTrue();
        assertThat(analyzer.isAmlLegalDomain("AML 内部调查指引是什么")).isTrue();
    }
}
