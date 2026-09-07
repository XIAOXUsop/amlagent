package com.bank.aml.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策表移植一致性：用与 docs/plans/examples/check-rapid-goods-v2-plan.mjs 相同的 21 个设计样例
 * 验证 Java 实现 {@link ExplanationDecisionRules} 与冻结设计口径一致（含守卫取反与顺序不敏感）。
 * 输入 JSON 中的布尔前提代表“已被服务与人工复核核对过的事实”，本测试不核验材料真实性。
 */
class ExplanationDecisionRulesTest {

    private static final Path SPEC = Path.of(
            "../docs/plans/examples/rapid-goods-v2-decision-cases.json");

    @Test
    void javaImplementationMatchesFrozenDesignCases() throws Exception {
        assertThat(Files.exists(SPEC)).as("设计样例文件必须存在：" + SPEC).isTrue();
        JsonNode spec = new ObjectMapper().readTree(Files.readString(SPEC));
        JsonNode defaults = spec.get("contextDefaults");
        JsonNode unitDefaults = spec.get("unitDefaults");

        int excludePositive = 0;
        int confirmPositive = 0;
        boolean hasNeutralCase = false;
        for (JsonNode sample : spec.get("cases")) {
            // caseStatus 在设计 JSON 中是字符串枚举（"HOLD"），不是布尔
            boolean caseStatusHold = "HOLD".equalsIgnoreCase(defaults.get("caseStatus").asText(""));
            ExplanationDecisionRules.CaseContext context = new ExplanationDecisionRules.CaseContext(
                    caseStatusHold, bool(defaults, "scopeEnumerated"),
                    bool(defaults, "adoptedFactsUsable"), bool(defaults, "reviewBasisCurrent"),
                    bool(defaults, "policyApplicable"), bool(defaults, "reviewerIndependent"),
                    bool(defaults, "otherScenarioGateSatisfied"), bool(defaults, "hasOpenDecisionSupportEdd"),
                    bool(defaults, "obligationTransferReady"), bool(defaults, "continuationPlanReady"));
            JsonNode overrides = sample.get("context");
            if (overrides != null && overrides.has("caseStatus")) {
                caseStatusHold = "HOLD".equalsIgnoreCase(overrides.get("caseStatus").asText(""));
            }
            if (overrides != null) {
                context = new ExplanationDecisionRules.CaseContext(
                        caseStatusHold,
                        contextOr(overrides, defaults, "scopeEnumerated", context.scopeEnumerated()),
                        contextOr(overrides, defaults, "adoptedFactsUsable", context.adoptedFactsUsable()),
                        contextOr(overrides, defaults, "reviewBasisCurrent", context.reviewBasisCurrent()),
                        contextOr(overrides, defaults, "policyApplicable", context.policyApplicable()),
                        contextOr(overrides, defaults, "reviewerIndependent", context.reviewerIndependent()),
                        contextOr(overrides, defaults, "otherScenarioGateSatisfied",
                                context.otherScenarioGateSatisfied()),
                        contextOr(overrides, defaults, "hasOpenDecisionSupportEdd",
                                context.hasOpenDecisionSupportEdd()),
                        contextOr(overrides, defaults, "obligationTransferReady",
                                context.obligationTransferReady()),
                        contextOr(overrides, defaults, "continuationPlanReady",
                                context.continuationPlanReady()));
            }
            List<ExplanationDecisionRules.UnitAssessment> units = new ArrayList<>();
            for (JsonNode unitNode : sample.get("units")) {
                units.add(new ExplanationDecisionRules.UnitAssessment(
                        ExplanationOutcome.valueOf(unitNode.get("outcome").asText()),
                        boolOr(unitNode, unitDefaults, "assessmentValid"),
                        boolOr(unitNode, unitDefaults, "criticalUnknown"),
                        boolOr(unitNode, unitDefaults, "suspicionBasisComplete"),
                        boolOr(unitNode, unitDefaults, "unresolvedDisclosed"),
                        boolOr(unitNode, unitDefaults, "followupRequired")));
            }
            ExplanationDecisionRules.Decision actual = ExplanationDecisionRules.evaluate(context, units);
            boolean expectedExclude = sample.get("expected").get("canExclude").asBoolean();
            boolean expectedConfirm = sample.get("expected").get("canConfirm").asBoolean();

            assertThat(actual.canExclude()).as(sample.get("id") + " canExclude").isEqualTo(expectedExclude);
            assertThat(actual.canConfirm()).as(sample.get("id") + " canConfirm").isEqualTo(expectedConfirm);
            // 方向互斥（§7.2）
            assertThat(actual.canExclude() && actual.canConfirm())
                    .as(sample.get("id") + " incompatible directions").isFalse();
            // 顺序不敏感
            List<ExplanationDecisionRules.UnitAssessment> reversed = new ArrayList<>(units);
            java.util.Collections.reverse(reversed);
            assertThat(ExplanationDecisionRules.evaluate(context, reversed))
                    .as(sample.get("id") + " ordering").isEqualTo(actual);
            // 关键门禁关闭时不得放行
            for (String guard : List.of("scopeEnumerated", "adoptedFactsUsable", "reviewBasisCurrent",
                    "policyApplicable", "reviewerIndependent", "otherScenarioGateSatisfied")) {
                ExplanationDecisionRules.CaseContext broken = withGuard(context, guard, false);
                assertThat(ExplanationDecisionRules.evaluate(broken, units))
                        .as(sample.get("id") + " guard " + guard)
                        .isEqualTo(new ExplanationDecisionRules.Decision(false, false));
            }
            if (expectedExclude) {
                excludePositive++;
            }
            if (expectedConfirm) {
                confirmPositive++;
            }
            if (!expectedExclude && !expectedConfirm) {
                hasNeutralCase = true;
            }
        }
        // 拒绝一切/放行一切的政策都不得“通过”本设计集（§18 V2-26 的设计层预检）
        assertThat(excludePositive).isGreaterThan(0);
        assertThat(confirmPositive).isGreaterThan(0);
        assertThat(hasNeutralCase).isTrue();
    }

    private ExplanationDecisionRules.CaseContext withGuard(ExplanationDecisionRules.CaseContext context,
                                                           String guard, boolean value) {
        return switch (guard) {
            case "scopeEnumerated" -> new ExplanationDecisionRules.CaseContext(context.caseStatusHold(),
                    value, context.adoptedFactsUsable(), context.reviewBasisCurrent(),
                    context.policyApplicable(), context.reviewerIndependent(),
                    context.otherScenarioGateSatisfied(), context.hasOpenDecisionSupportEdd(),
                    context.obligationTransferReady(), context.continuationPlanReady());
            case "adoptedFactsUsable" -> new ExplanationDecisionRules.CaseContext(context.caseStatusHold(),
                    context.scopeEnumerated(), value, context.reviewBasisCurrent(),
                    context.policyApplicable(), context.reviewerIndependent(),
                    context.otherScenarioGateSatisfied(), context.hasOpenDecisionSupportEdd(),
                    context.obligationTransferReady(), context.continuationPlanReady());
            case "reviewBasisCurrent" -> new ExplanationDecisionRules.CaseContext(context.caseStatusHold(),
                    context.scopeEnumerated(), context.adoptedFactsUsable(), value,
                    context.policyApplicable(), context.reviewerIndependent(),
                    context.otherScenarioGateSatisfied(), context.hasOpenDecisionSupportEdd(),
                    context.obligationTransferReady(), context.continuationPlanReady());
            case "policyApplicable" -> new ExplanationDecisionRules.CaseContext(context.caseStatusHold(),
                    context.scopeEnumerated(), context.adoptedFactsUsable(), context.reviewBasisCurrent(),
                    value, context.reviewerIndependent(), context.otherScenarioGateSatisfied(),
                    context.hasOpenDecisionSupportEdd(), context.obligationTransferReady(),
                    context.continuationPlanReady());
            case "reviewerIndependent" -> new ExplanationDecisionRules.CaseContext(context.caseStatusHold(),
                    context.scopeEnumerated(), context.adoptedFactsUsable(), context.reviewBasisCurrent(),
                    context.policyApplicable(), value, context.otherScenarioGateSatisfied(),
                    context.hasOpenDecisionSupportEdd(), context.obligationTransferReady(),
                    context.continuationPlanReady());
            case "otherScenarioGateSatisfied" -> new ExplanationDecisionRules.CaseContext(
                    context.caseStatusHold(), context.scopeEnumerated(), context.adoptedFactsUsable(),
                    context.reviewBasisCurrent(), context.policyApplicable(), context.reviewerIndependent(),
                    value, context.hasOpenDecisionSupportEdd(), context.obligationTransferReady(),
                    context.continuationPlanReady());
            default -> throw new IllegalArgumentException(guard);
        };
    }

    private static boolean contextOr(JsonNode overrides, JsonNode defaults, String field, boolean fallback) {
        JsonNode value = overrides.get(field);
        return value == null ? fallback : value.asBoolean();
    }

    private static boolean boolOr(JsonNode unitNode, JsonNode unitDefaults, String field) {
        JsonNode value = unitNode.get(field);
        JsonNode fallback = unitDefaults.get(field);
        if (value == null || value.isNull()) {
            return fallback == null ? false : fallback.asBoolean();
        }
        return value.asBoolean();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }
}
