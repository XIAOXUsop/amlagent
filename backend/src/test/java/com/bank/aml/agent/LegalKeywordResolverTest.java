package com.bank.aml.agent;

import com.bank.aml.domain.InvestigationAlertSnapshot;
import com.bank.aml.domain.RiskContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegalKeywordResolverTest {

    private final LegalKeywordResolver resolver = new LegalKeywordResolver();

    @Test
    void resolvesCrossBorderAndNightKeywords() {
        assertThat(resolver.resolve("大额频繁跨国转账、夜间集中交易")).contains("跨境", "夜间");
    }

    @Test
    void resolvesSplitAndCashKeywords() {
        assertThat(resolver.resolve("现金频繁存取、涉嫌拆分交易")).contains("现金", "拆分", "可疑交易");
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

    @Test
    void sanctionFactsAddLegalTopicsEvenWhenAlertTextDoesNotMentionSanctions() {
        RiskContext riskFacts = new RiskContext(1, true, 0, 0, 0, true, false, 0, 0, null, 0);

        assertThat(resolver.resolve("大额频繁跨国转账、夜间集中交易", riskFacts)).contains("跨境", "夜间", "资产冻结", "停止金融服务", "冻结措施报告");
    }

    /** T05：归并后逐条预警的关键词与场景主题都保留，去重且顺序稳定。 */
    @Test
    void frozenAlertsKeepPerAlertKeywordsAndScenarioTopics() {
        RiskContext riskFacts = new RiskContext(0, false, 0, 0, 0, true, true, 0, 0, "低风险", 1);
        var crossBorder = new InvestigationAlertSnapshot(11L, "ALERT-A", "RULE-001", "CROSS_BORDER_ANOMALY",
                "客户连续发生夜间跨境转账", LocalDateTime.of(2026, 8, 1, 23, 0), 0);
        var structuring = new InvestigationAlertSnapshot(12L, "ALERT-B", "RULE-002", "STRUCTURING", "客户通过拆分现金交易规避监测",
                LocalDateTime.of(2026, 8, 1, 10, 0), 0);

        var keywords = resolver.resolve(List.of(structuring, crossBorder), riskFacts);

        assertThat(keywords).contains("夜间", "跨境", "拆分", "现金");
        // 主题顺序由生产上游的稳定预警排序保证；对同一集合，乱序输入产生相同的主题集合
        assertThat(resolver.resolve(List.of(crossBorder, structuring), riskFacts))
            .containsExactlyInAnyOrderElementsOf(keywords);
    }

    @Test
    void scenarioCodeAloneContributesDeterministicTopic() {
        RiskContext riskFacts = new RiskContext(0, false, 0, 0, 0, true, true, 0, 0, "低风险", 1);
        var sanctions = new InvestigationAlertSnapshot(13L, "ALERT-C", "RULE-003", "SANCTIONS_WATCHLIST", "RULE-003",
                LocalDateTime.of(2026, 8, 1, 12, 0), 0);

        // 规则编号只是标识，不要求可被中文关键词解析；场景映射仍给出“制裁名单”主题
        assertThat(resolver.resolve(List.of(sanctions), riskFacts)).contains("制裁名单");
    }

}
