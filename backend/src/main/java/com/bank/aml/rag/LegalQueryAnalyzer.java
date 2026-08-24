package com.bank.aml.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性法规查询分析：同义词展开、领域短语/金额/期限/文号/条号/主体/行为抽取，以及问题类型判定。
 * <p>不允许 LLM 生成权限/过滤条件；所有解析均由规则产生，供字段化检索与支持度阈值选择使用。</p>
 */
@Component
public class LegalQueryAnalyzer {
    private static final List<String> DOMAIN_TERMS = List.of(
            "大额交易", "可疑交易", "跨境", "现金", "受益所有人", "最终受益人", "实际控制人",
            "股权", "多层嵌套", "尽职调查", "高风险", "资金来源", "交易记录", "身份识别",
            "保存", "冻结", "恐怖活动", "制裁名单", "名单比对", "报告时限", "工作日",
            "中国人民银行", "监督管理", "罚款", "保密");
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();
    private static final Pattern NUMBER_TERM = Pattern.compile("[0-9一二三四五六七八九十百千万]+(?:万元|万美元|年|个工作日)");
    private static final Pattern DOC_NUMBER = Pattern.compile("[^，。；、\\s]{0,30}令[〔【]?\\d{4}[〕】]?第\\d+号");
    private static final Pattern ARTICLE_NUMBER = Pattern.compile("第[一二三四五六七八九十百零〇0-9]+条");
    private static final Pattern REGULATION_NAME = Pattern.compile("[\\u4e00-\\u9fa5]{2,20}(?:法|办法|通知|规定|指引|条例)");
    private static final Pattern AMOUNT = Pattern.compile("[0-9一二三四五六七八九十百千万]+(?:万元|万美元|欧元|美元|元)");
    private static final Pattern DURATION = Pattern.compile("[0-9一二三四五六七八九十]+(?:年|个月|个工作日|日内|天)");
    private static final List<String> SUBJECT_TERMS = List.of(
            "自然人", "非自然人客户", "公司账户", "企业账户", "金融机构", "银行", "客户", "法人");
    private static final List<String> NEGATION_TERMS = List.of("不得", "禁止", "不得办理");
    private static final List<String> HIGH_RISK_DISPOSAL_TERMS = List.of(
            "立即冻结", "等待人工审批", "等待审批", "采取冻结", "解除冻结", "能否冻结", "是否冻结",
            "立即处理", "高风险处置", "应当立即", "如何处置", "怎么处置");
    private static final List<String> GENERAL_KNOWLEDGE_TERMS = List.of(
            "是什么", "什么是", "如何", "怎么", "介绍一下", "常见", "适合");

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

    /** 问题类型：决定支持度阈值与判定策略。 */
    public enum QueryIntent {
        /** 法规事实查询：回答“是否/金额/期限/程序”等规范性事实 */
        REGULATION_FACT,
        /** 高风险处置依据：冻结、制裁、可疑交易、立即处置等敏感动作 */
        HIGH_RISK_DISPOSAL,
        /** 一般金融知识：无明确法规依据的公开常识问答 */
        GENERAL_KNOWLEDGE
    }

    /** 确定性解析结果：字段级查询要素与问题类型。 */
    public record ParsedQuery(
            QueryIntent intent,
            List<String> terms,
            List<String> regulations,
            List<String> docNumbers,
            List<String> articleNumbers,
            List<String> amounts,
            List<String> durations,
            List<String> subjects,
            List<String> actions,
            List<String> negations
    ) {
        public boolean expectsAuthority() {
            return !regulations.isEmpty() || !docNumbers.isEmpty() || !articleNumbers.isEmpty();
        }
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

    /** 字段级确定性解析：意图 + 法规/文号/条号/金额/期限/主体/行为/否定。 */
    public ParsedQuery parse(String query) {
        if (query == null || query.isBlank()) {
            return new ParsedQuery(QueryIntent.REGULATION_FACT, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<String> regulations = collect(REGULATION_NAME, normalized);
        List<String> docNumbers = collect(DOC_NUMBER, normalized);
        List<String> articleNumbers = collect(ARTICLE_NUMBER, normalized);
        List<String> amounts = collect(AMOUNT, normalized);
        List<String> durations = collect(DURATION, normalized);
        List<String> subjects = SUBJECT_TERMS.stream().filter(normalized::contains).map(String::trim).toList();
        List<String> actions = DOMAIN_ACTIONS.stream().filter(normalized::contains).toList();
        List<String> negations = NEGATION_TERMS.stream().filter(normalized::contains).toList();
        QueryIntent intent = intent(normalized);
        return new ParsedQuery(intent, terms(query), regulations, docNumbers, articleNumbers,
                amounts, durations, subjects, actions, negations);
    }

    /**
     * 当前知识库只覆盖 AML 法律与银行反洗钱义务。没有任何 AML/银行法规信号的问题必须在召回前拒答，
     * 不能仅凭向量模型对无关问题给出的偶然高余弦分数进入支持判定。
     */
    public boolean isAmlLegalDomain(String query) {
        if (query == null || query.isBlank()) return false;
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (DOMAIN_TERMS.stream().anyMatch(normalized::contains)) return true;
        if (SYNONYMS.keySet().stream().anyMatch(normalized::contains)) return true;
        // “银行/金融机构”本身过宽（理财、信用卡等均不属于本 AML 法规库），必须伴随更具体的 AML 信号。
        if (containsAny(normalized, "aml", "反洗钱", "客户身份", "交易的实际受益人")) return true;
        // “某某规定/第X条/某年某号令”也可能属于劳动、税务等其他法律域，不能单独作为 AML 信号。
        return false;
    }

    private static final List<String> DOMAIN_ACTIONS = List.of(
            "应当", "必须", "不得", "禁止", "可以", "立即", "报告", "保存", "识别", "核实",
            "冻结", "报送", "审查", "监测", "提示");

    private QueryIntent intent(String normalized) {
        // “冻结/恐怖活动/名单”等名词也会出现在保密义务、报告对象等事实查询中。
        // 只有问题明确要求执行、等待、解除或立即处置时才采用更高的处置阈值，
        // 避免把“冻结措施能否提前告知客户”误判成处置建议。
        if (HIGH_RISK_DISPOSAL_TERMS.stream().anyMatch(normalized::contains)
                || (normalized.contains("冻结") && containsAny(normalized,
                "是否应当", "能否等待", "必须立即", "可以等待", "如何处理", "怎么处理"))) {
            return QueryIntent.HIGH_RISK_DISPOSAL;
        }
        if (GENERAL_KNOWLEDGE_TERMS.stream().anyMatch(normalized::contains)
                && !DOMAIN_TERMS.stream().anyMatch(normalized::contains)) {
            return QueryIntent.GENERAL_KNOWLEDGE;
        }
        return QueryIntent.REGULATION_FACT;
    }

    private List<String> collect(Pattern pattern, String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String match = matcher.group().trim();
            if (match.length() >= 2) result.add(match);
        }
        return List.copyOf(result);
    }

    private void add(List<String> values, String value) {
        if (value != null && value.length() >= 2 && !values.contains(value)) values.add(value);
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
