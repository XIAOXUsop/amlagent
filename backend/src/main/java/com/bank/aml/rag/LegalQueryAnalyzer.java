package com.bank.aml.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 确定性法规查询分析：同义词展开和领域短语提取，不允许 LLM 生成权限/过滤条件。 */
@Component
public class LegalQueryAnalyzer {
    private static final List<String> DOMAIN_TERMS = List.of(
            "大额交易", "可疑交易", "跨境", "现金", "受益所有人", "最终受益人", "实际控制人",
            "股权", "多层嵌套", "尽职调查", "高风险", "资金来源", "交易记录", "身份识别",
            "保存", "冻结", "恐怖活动", "制裁名单", "名单比对", "报告时限", "工作日",
            "中国人民银行", "监督管理", "罚款", "保密");
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();
    private static final Pattern NUMBER_TERM = Pattern.compile("[0-9一二三四五六七八九十百千万]+(?:万元|万美元|年|个工作日)");

    static {
        SYNONYMS.put("ubo", "受益所有人");
        SYNONYMS.put("最终受益人", "受益所有人");
        SYNONYMS.put("实际受益人", "交易的实际受益人");
        SYNONYMS.put("公司账户", "非自然人客户");
        SYNONYMS.put("企业账户", "非自然人客户");
        SYNONYMS.put("一天累计", "当日累计");
        SYNONYMS.put("多久", "报告时限");
        SYNONYMS.put("主管机构", "监督管理");
        SYNONYMS.put("监管机构", "监督管理");
        SYNONYMS.put("等待人工审批", "立即");
        SYNONYMS.put("等待审批", "立即");
    }

    public List<String> terms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        SYNONYMS.forEach((source, target) -> {
            if (normalized.contains(source)) add(result, target);
        });
        DOMAIN_TERMS.stream().filter(normalized::contains).forEach(term -> add(result, term));
        Matcher matcher = NUMBER_TERM.matcher(normalized);
        while (matcher.find()) add(result, matcher.group());
        if (result.isEmpty()) add(result, normalized.length() <= 32 ? normalized : normalized.substring(0, 32));
        return result.stream().limit(8).toList();
    }

    private void add(List<String> values, String value) {
        if (value != null && value.length() >= 2 && !values.contains(value)) values.add(value);
    }
}
