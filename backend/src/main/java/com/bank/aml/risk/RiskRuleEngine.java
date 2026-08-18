package com.bank.aml.risk;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风险规则引擎：加载启用的规则，按优先级评估 {@link RiskContext}，返回命中的规则及证据。
 * <p>条件表达式为简单 DSL，如：{@code sanction.maxSeverity == 1 && transaction.crossRatio > 20}
 * 支持字段：sanction.maxSeverity / sanction.sanctionHit / transaction.crossRatio /
 * transaction.nightRatio / transaction.largeCount / transaction.dataComplete /
 * transaction.riskExplained / transaction.patternSeverity / corporate.uboRiskSeverity
 */
@Component
public class RiskRuleEngine {

    private static final Pattern CONDITION_PATTERN = Pattern.compile("^(\\w+\\.\\w+)\\s*(==|>=|<=|>|<)\\s*([\\w.]+)$");
    private static final Set<String> SUPPORTED_FIELDS = Set.of(
            "sanction.maxSeverity",
            "sanction.sanctionHit",
            "transaction.crossRatio",
            "transaction.nightRatio",
            "transaction.largeCount",
            "transaction.dataComplete",
            "transaction.riskExplained",
            "transaction.patternSeverity",
            "corporate.uboRiskSeverity"
    );

    private final RiskRuleRepository repository;
    /** 启用规则缓存：规则表低频变更，避免每次 Guardrails 评估都查库；TTL 后自动刷新 */
    private static final long RULE_CACHE_TTL_MS = 60_000L;
    private volatile List<RiskRule> cachedRules = List.of();
    private volatile long cachedAt = 0L;

    public RiskRuleEngine(RiskRuleRepository repository) {
        this.repository = repository;
    }

    /** 触发的规则 */
    public record TriggeredRule(String ruleCode, int ruleVersion, String targetRiskLevel,
                                String action, String evidence) {
    }

    public List<TriggeredRule> evaluate(RiskContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        List<TriggeredRule> triggered = new ArrayList<>();
        for (RiskRule r : activeRules()) {
            if (r.getEffectiveFrom() != null && now.isBefore(r.getEffectiveFrom())) {
                continue;
            }
            if (r.getEffectiveTo() != null && now.isAfter(r.getEffectiveTo())) {
                continue;
            }
            if (evaluate(r.getConditionExpression(), ctx)) {
                triggered.add(new TriggeredRule(r.getRuleCode(), r.getVersion(),
                        r.getTargetRiskLevel(), r.getAction(), evidenceText(ctx, r)));
            }
        }
        return triggered;
    }

    /** 带 TTL 的启用规则缓存；并发下允许短暂读到旧快照（规则低频变更，可接受） */
    private List<RiskRule> activeRules() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > RULE_CACHE_TTL_MS) {
            cachedRules = repository.findByEnabledTrueOrderByPriorityAsc();
            cachedAt = now;
        }
        return cachedRules;
    }

    /** 供规则种子器在启动时主动失效缓存，避免首轮 Guardrails 评估读到空快照 */
    public void invalidateCache() {
        cachedAt = 0L;
    }

    /** 表达式评估：|| 优先级低于 && */
    public boolean evaluate(String expr, RiskContext ctx) {
        validateExpression(expr);
        for (String orPart : expr.split("\\|\\|")) {
            boolean and = true;
            for (String andPart : orPart.split("&&")) {
                if (!evalSingle(andPart.trim(), ctx)) {
                    and = false;
                    break;
                }
            }
            if (and) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在规则执行前校验 DSL。无效字段不能按 0 处理，否则类似
     * {@code transaction.typo == false} 的拼写错误会意外命中所有客户。
     */
    public void validateExpression(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Risk rule expression must not be blank");
        }
        for (String orPart : expr.split("\\|\\|", -1)) {
            if (orPart.isBlank()) {
                throw new IllegalArgumentException("Invalid risk rule expression: " + expr);
            }
            for (String andPart : orPart.split("&&", -1)) {
                Matcher matcher = CONDITION_PATTERN.matcher(andPart.trim());
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Invalid risk rule condition: " + andPart.trim());
                }
                if (!SUPPORTED_FIELDS.contains(matcher.group(1))) {
                    throw new IllegalArgumentException("Unsupported risk rule field: " + matcher.group(1));
                }
                if (!Double.isFinite(parseValue(matcher.group(3)))) {
                    throw new IllegalArgumentException("Invalid risk rule value: " + matcher.group(3));
                }
            }
        }
    }

    private boolean evalSingle(String cond, RiskContext ctx) {
        Matcher m = CONDITION_PATTERN.matcher(cond);
        if (!m.find()) {
            return false;
        }
        double lhs = fieldValue(m.group(1), ctx);
        String op = m.group(2);
        double rhs = parseValue(m.group(3));
        // 未知字段或非法值必须安全失败，避免“拼错字段 == false”意外全量命中。
        if (!Double.isFinite(lhs) || !Double.isFinite(rhs)) {
            return false;
        }
        return switch (op) {
            case "==" -> lhs == rhs;
            case ">" -> lhs > rhs;
            case ">=" -> lhs >= rhs;
            case "<" -> lhs < rhs;
            case "<=" -> lhs <= rhs;
            default -> false;
        };
    }

    private double fieldValue(String field, RiskContext ctx) {
        return switch (field) {
            case "sanction.maxSeverity" -> ctx.maxSeverity();
            case "sanction.sanctionHit" -> ctx.sanctionHit() ? 1 : 0;
            case "transaction.crossRatio" -> ctx.crossRatio();
            case "transaction.nightRatio" -> ctx.nightRatio();
            case "transaction.largeCount" -> ctx.largeCount();
            case "transaction.dataComplete" -> ctx.transactionDataComplete() ? 1 : 0;
            case "transaction.riskExplained" -> ctx.transactionRiskExplained() ? 1 : 0;
            case "transaction.patternSeverity" -> ctx.transactionPatternSeverity();
            case "corporate.uboRiskSeverity" -> ctx.uboRiskSeverity();
            default -> Double.NaN;
        };
    }

    private double parseValue(String v) {
        if ("true".equalsIgnoreCase(v)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(v)) {
            return 0;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String evidenceText(RiskContext ctx, RiskRule r) {
        return switch (r.getRuleCode()) {
            case "SANCTION_LEVEL_1" -> "命中一级制裁名单（OFAC SDN）";
            case "SANCTION_OTHER" -> "命中国内制裁 / 可疑交易名单";
            case "TXN_ABNORMAL" -> "跨境占比 " + ctx.crossRatio() + "% 且夜间占比 " + ctx.nightRatio() + "%";
            case "TXN_PATTERN_HIGH" -> "命中高严重度可疑交易模式";
            case "TXN_MODERATE" -> "命中需加强监测的交易模式";
            case "CROSS_BORDER_MODERATE" -> "未充分解释的跨境交易占比 " + ctx.crossRatio() + "%";
            case "NIGHT_ACTIVITY_MODERATE" -> "未充分解释的夜间交易占比 " + ctx.nightRatio() + "%";
            case "DATA_INCOMPLETE" -> "交易数据不完整，无法排除异常";
            case "UBO_UNVERIFIED" -> "受益所有人无法可靠核实";
            case "UBO_DOCUMENT_INCOMPLETE" -> "受益所有人材料需要更新";
            default -> "规则条件触发";
        };
    }
}
