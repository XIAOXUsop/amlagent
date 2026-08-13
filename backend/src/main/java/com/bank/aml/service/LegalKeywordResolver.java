package com.bank.aml.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预警规则 → 法规检索关键词映射。
 * <p>生产链路与评测链路对齐：模型调用 searchLegal 时，query 必须逐字包含这里给出的关键词，
 * 避免模型猜测关键词导致反复重试与 Token 浪费（首轮评测已定位该问题）。
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
}
