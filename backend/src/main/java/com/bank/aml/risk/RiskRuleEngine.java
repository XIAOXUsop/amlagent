package com.bank.aml.risk;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风险规则引擎：加载启用的规则，按优先级评估 {@link RiskContext}，返回命中的规则及证据。
 * <p>条件表达式为简单 DSL，如：{@code sanction.maxSeverity == 1 && transaction.crossRatio > 20}
 * 支持字段：sanction.maxSeverity / sanction.sanctionHit / transaction.crossRatio / transaction.nightRatio / transaction.largeCount
 */
@Component
public class RiskRuleEngine {

    private static final Pattern CONDITION_PATTERN = Pattern.compile("^(\\w+\\.\\w+)\\s*(==|>=|<=|>|<)\\s*([\\w.]+)$");

    private final RiskRuleRepository repository;

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
        for (RiskRule r : repository.findByEnabledTrueOrderByPriorityAsc()) {
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

    /** 表达式评估：|| 优先级低于 && */
    public boolean evaluate(String expr, RiskContext ctx) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
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

    private boolean evalSingle(String cond, RiskContext ctx) {
        Matcher m = CONDITION_PATTERN.matcher(cond);
        if (!m.find()) {
            return false;
        }
        double lhs = fieldValue(m.group(1), ctx);
        String op = m.group(2);
        double rhs = parseValue(m.group(3));
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
            default -> 0;
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
            return 0;
        }
    }

    private String evidenceText(RiskContext ctx, RiskRule r) {
        return switch (r.getRuleCode()) {
            case "SANCTION_LEVEL_1" -> "命中一级制裁名单（OFAC SDN）";
            case "SANCTION_OTHER" -> "命中国内制裁 / 可疑交易名单";
            case "TXN_ABNORMAL" -> "跨境占比 " + ctx.crossRatio() + "% 且夜间占比 " + ctx.nightRatio() + "%";
            case "TXN_MODERATE" -> "跨境/夜间交易占比 " + ctx.crossRatio() + "% / " + ctx.nightRatio() + "%";
            default -> "规则条件触发";
        };
    }
}
