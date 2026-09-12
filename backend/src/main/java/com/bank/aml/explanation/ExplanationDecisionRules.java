package com.bank.aml.explanation;

import java.util.ArrayList;
import java.util.List;

/**
 * 案件层决策表（v2 计划 §7.2）：检查建议及依据是否具备可复核条件，不自动化业务判断。 与
 * docs/plans/examples/check-rapid-goods-v2-plan.mjs 的 evaluateDesign 保持同一口径；
 * {@code ExplanationDecisionRulesTest} 用同一份 21 个决策样例 JSON 验证两种实现一致。
 */
public final class ExplanationDecisionRules {

    /** 案件层前提（与设计样例 JSON contextDefaults 对应）。 */
    public record CaseContext(boolean caseStatusHold, boolean scopeEnumerated, boolean adoptedFactsUsable,
            boolean reviewBasisCurrent, boolean policyApplicable, boolean reviewerIndependent,
            boolean otherScenarioGateSatisfied, boolean hasOpenDecisionSupportEdd, boolean obligationTransferReady,
            boolean continuationPlanReady) {
        public static CaseContext freshDefaults() {
            return new CaseContext(true, true, true, true, true, true, true, false, false, false);
        }
    }

    /** 预警单元评估输入（与设计样例 JSON unitDefaults 对应）。 */
    public record UnitAssessment(ExplanationOutcome outcome, boolean assessmentValid, boolean criticalUnknown,
            boolean suspicionBasisComplete, boolean unresolvedDisclosed, boolean followupRequired) {
        public static UnitAssessment of(ExplanationOutcome outcome) {
            return new UnitAssessment(outcome, true, false, false, false, false);
        }
    }

    public record Decision(boolean canExclude, boolean canConfirm) {
    }

    private ExplanationDecisionRules() {
    }

    public static Decision evaluate(CaseContext context, List<UnitAssessment> units) {
        boolean eligible = context.caseStatusHold() && context.scopeEnumerated() && context.adoptedFactsUsable()
                && context.reviewBasisCurrent() && context.policyApplicable() && context.reviewerIndependent()
                && context.otherScenarioGateSatisfied() && !units.isEmpty()
                && units.stream().allMatch(UnitAssessment::assessmentValid);
        boolean taskGate = !context.hasOpenDecisionSupportEdd()
                || (context.obligationTransferReady() && context.continuationPlanReady());
        var explained = new ArrayList<UnitAssessment>();
        for (UnitAssessment unit : units) {
            if (unit.outcome() == ExplanationOutcome.EXPLAINED && explained(unit, context.continuationPlanReady())) {
                explained.add(unit);
            }
        }
        boolean canConfirmSuspicionPresent = units.stream()
            .anyMatch(unit -> unit.outcome() == ExplanationOutcome.SUSPICIOUS && unit.suspicionBasisComplete());
        boolean allEvaluableForSuspicion = units.stream()
            .allMatch(unit -> evaluableForSuspicion(unit, context.continuationPlanReady()));
        boolean canExclude = eligible && taskGate && explained.size() == units.size();
        boolean canConfirm = eligible && taskGate && canConfirmSuspicionPresent && allEvaluableForSuspicion;
        return new Decision(canExclude, canConfirm);
    }

    private static boolean explained(UnitAssessment unit, boolean continuationPlanReady) {
        return unit.outcome() == ExplanationOutcome.EXPLAINED && !unit.criticalUnknown()
                && followupReady(unit, continuationPlanReady);
    }

    private static boolean evaluableForSuspicion(UnitAssessment unit, boolean continuationPlanReady) {
        if (unit.outcome() == ExplanationOutcome.EXPLAINED) {
            return explained(unit, continuationPlanReady);
        }
        if (unit.outcome() == ExplanationOutcome.SUSPICIOUS) {
            return unit.suspicionBasisComplete() && followupReady(unit, continuationPlanReady)
                    && (!unit.criticalUnknown() || unit.unresolvedDisclosed());
        }
        // UNRESOLVED：只有在未知被显式披露且跟进安排就绪时，才允许随可疑决定一起进入最终确认
        return unit.unresolvedDisclosed() && followupReady(unit, continuationPlanReady);
    }

    private static boolean followupReady(UnitAssessment unit, boolean continuationPlanReady) {
        boolean needsFollowup = unit.followupRequired() || unit.outcome() == ExplanationOutcome.UNRESOLVED
                || unit.criticalUnknown();
        return !needsFollowup || continuationPlanReady;
    }

}
