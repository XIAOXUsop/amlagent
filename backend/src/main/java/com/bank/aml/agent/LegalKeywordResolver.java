package com.bank.aml.agent;

import com.bank.aml.domain.InvestigationAlertSnapshot;
import com.bank.aml.domain.RiskContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 预警规则 → 法规检索关键词映射。
 * <p>
 * 生产链路与评测链路对齐：模型调用 searchLegal 时，query 必须逐字包含这里给出的关键词， 避免模型猜测关键词导致反复重试与 Token
 * 浪费（首轮评测已定位该问题）。
 */
@Component
public class LegalKeywordResolver {

    /** 预警特征词 → 法规检索关键词（有序，命中即输出对应关键词） */
    private static final Map<String, String> KEYWORD_BY_FEATURE = new LinkedHashMap<>();

    static {
        KEYWORD_BY_FEATURE.put("跨境", "跨境");
        KEYWORD_BY_FEATURE.put("跨国", "跨境");
        KEYWORD_BY_FEATURE.put("境外", "跨境");
        KEYWORD_BY_FEATURE.put("夜间", "夜间");
        KEYWORD_BY_FEATURE.put("拆分", "拆分");
        KEYWORD_BY_FEATURE.put("现金", "现金");
        KEYWORD_BY_FEATURE.put("分层", "分层");
        KEYWORD_BY_FEATURE.put("制裁", "制裁");
        KEYWORD_BY_FEATURE.put("黑名单", "名单");
        KEYWORD_BY_FEATURE.put("名单", "名单");
        KEYWORD_BY_FEATURE.put("受益所有人", "受益所有人");
        KEYWORD_BY_FEATURE.put("实际控制人", "实际控制人");
        KEYWORD_BY_FEATURE.put("股权", "股权");
        KEYWORD_BY_FEATURE.put("可疑", "可疑交易");
        KEYWORD_BY_FEATURE.put("涉嫌", "可疑交易");
        KEYWORD_BY_FEATURE.put("洗钱", "可疑交易");
        KEYWORD_BY_FEATURE.put("尽职调查", "尽职调查");
        KEYWORD_BY_FEATURE.put("资金来源", "资金来源");
        KEYWORD_BY_FEATURE.put("快进快出", "交易模式");
    }

    /** 根据预警规则解析出法规检索关键词（至少返回 2 个默认关键词） */
    public List<String> resolve(String alertRule) {
        List<String> keywords = new ArrayList<>();
        if (alertRule != null) {
            for (Map.Entry<String, String> entry : KEYWORD_BY_FEATURE.entrySet()) {
                if (alertRule.contains(entry.getKey()) && !keywords.contains(entry.getValue())) {
                    keywords.add(entry.getValue());
                }
            }
        }
        if (keywords.isEmpty()) {
            keywords.add("尽职调查");
            keywords.add("风险评估");
        }
        return keywords;
    }

    /**
     * 将预警文本与已经冻结的确定性风险事实合并为法规主题。 制裁事实不能依赖预警文案是否写出“制裁”二字，否则后端规则补入冻结/停止服务动作时可能没有对应法规证据。
     */
    public List<String> resolve(String alertRule, RiskContext riskFacts) {
        List<String> keywords = new ArrayList<>(resolve(alertRule));
        if (riskFacts != null && riskFacts.maxSeverity() == 1) {
            // 一级名单会由规则补入三类高影响动作，检索词必须直指这些动作的法律前提，不能只查泛化的“名单”。
            addIfMissing(keywords, "资产冻结");
            addIfMissing(keywords, "停止金融服务");
            addIfMissing(keywords, "冻结措施报告");
        }
        else if (riskFacts != null && riskFacts.sanctionHit()) {
            addIfMissing(keywords, "制裁名单");
        }
        return List.copyOf(keywords);
    }

    /**
     * 生产入口：从本次执行冻结的全部关联预警提取法规主题。 每条预警的命中原因独立提取特征词，再按 scenarioCode 确定性映射补充主题；
     * 与风险事实产生的强制主题合并、去重并保持稳定顺序（先预警顺序，后风险事实补充）。
     */
    public List<String> resolve(List<InvestigationAlertSnapshot> alerts, RiskContext riskFacts) {
        List<String> keywords = new ArrayList<>();
        if (alerts != null) {
            for (InvestigationAlertSnapshot alert : alerts) {
                if (alert == null)
                    continue;
                String text = (alert.hitReason() == null ? "" : alert.hitReason()) + " "
                        + (alert.ruleCode() == null ? "" : alert.ruleCode());
                for (Map.Entry<String, String> entry : KEYWORD_BY_FEATURE.entrySet()) {
                    if (text.contains(entry.getKey())) {
                        addIfMissing(keywords, entry.getValue());
                    }
                }
                String scenarioTopic = TOPIC_BY_SCENARIO.get(alert.scenarioCode() == null ? "" : alert.scenarioCode());
                if (scenarioTopic != null) {
                    addIfMissing(keywords, scenarioTopic);
                }
            }
        }
        if (keywords.isEmpty()) {
            keywords.add("尽职调查");
            keywords.add("风险评估");
        }
        if (riskFacts != null && riskFacts.maxSeverity() == 1) {
            addIfMissing(keywords, "资产冻结");
            addIfMissing(keywords, "停止金融服务");
            addIfMissing(keywords, "冻结措施报告");
        }
        else if (riskFacts != null && riskFacts.sanctionHit()) {
            addIfMissing(keywords, "制裁名单");
        }
        return List.copyOf(keywords);
    }

    /** scenarioCode → 法规主题的确定性映射（与 Playbook 场景闭集对应）。 */
    private static final Map<String, String> TOPIC_BY_SCENARIO = buildScenarioTopics();

    private static Map<String, String> buildScenarioTopics() {
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("STRUCTURING", "拆分");
        topics.put("RAPID_MOVEMENT", "可疑交易");
        topics.put("CROSS_BORDER_ANOMALY", "跨境");
        topics.put("PROFILE_MISMATCH", "尽职调查");
        topics.put("COMPLEX_OWNERSHIP", "受益所有人");
        topics.put("SANCTIONS_WATCHLIST", "制裁名单");
        topics.put("MANUAL_ALERT", "尽职调查");
        return Collections.unmodifiableMap(topics);
    }

    private void addIfMissing(List<String> keywords, String keyword) {
        if (!keywords.contains(keyword))
            keywords.add(keyword);
    }

}
