import { describe, expect, it } from 'vitest'
import {
  evaluateDecision, hypothesisAggregate, isExplanationRevisionConflict,
  type CaseDecisionContext, type Decision, type UnitDecisionInput,
} from './explanation'

/**
 * 与 docs/plans/examples/check-rapid-goods-v2-plan.mjs 相同的 21 个设计样例：
 * 验证前端决策表镜像与冻结设计口径一致（含守卫取反与顺序不敏感）。
 * 输入布尔值代表已被服务与人工复核核对过的事实，不是材料真实性核验。
 */
// 与 docs/plans/examples/rapid-goods-v2-decision-cases.json 冻结设计一致的 21 个样例
// （内联副本：与后端 ExplanationDecisionRulesTest 使用同一决策表口径；修改样例必须两处同步）。
const designCases: Array<{
  id: string
  context?: Partial<Record<string, unknown>>
  units: Array<Partial<UnitDecisionInput>>
  expected: Decision
}> = [
  { id: 'DG-01', units: [{ outcome: 'EXPLAINED' }], expected: { canExclude: true, canConfirm: false } },
  { id: 'DG-02', context: { continuationPlanReady: true }, units: [{ outcome: 'EXPLAINED', followupRequired: true }], expected: { canExclude: true, canConfirm: false } },
  { id: 'DG-03', units: [{ outcome: 'UNRESOLVED', criticalUnknown: true }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-04', units: [{ outcome: 'EXPLAINED' }, { outcome: 'SUSPICIOUS', suspicionBasisComplete: true }], expected: { canExclude: false, canConfirm: true } },
  { id: 'DG-05', units: [{ outcome: 'EXPLAINED' }, { outcome: 'UNRESOLVED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-06', units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: true }, { outcome: 'UNRESOLVED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-07', context: { continuationPlanReady: true }, units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: true }, { outcome: 'UNRESOLVED', unresolvedDisclosed: true }], expected: { canExclude: false, canConfirm: true } },
  { id: 'DG-08', units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: false }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-09', context: { scopeEnumerated: false }, units: [{ outcome: 'EXPLAINED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-10', context: { reviewBasisCurrent: false }, units: [{ outcome: 'EXPLAINED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-11', context: { reviewerIndependent: false }, units: [{ outcome: 'EXPLAINED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-12', context: { policyApplicable: false }, units: [{ outcome: 'EXPLAINED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-13', units: [{ outcome: 'EXPLAINED', criticalUnknown: true }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-14', context: { hasOpenDecisionSupportEdd: true, continuationPlanReady: true }, units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: true }, { outcome: 'UNRESOLVED', unresolvedDisclosed: true }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-15', context: { hasOpenDecisionSupportEdd: true, continuationPlanReady: true, obligationTransferReady: true }, units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: true }, { outcome: 'UNRESOLVED', unresolvedDisclosed: true }], expected: { canExclude: false, canConfirm: true } },
  { id: 'DG-16', units: [{ outcome: 'EXPLAINED', followupRequired: true }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-17', context: { adoptedFactsUsable: false }, units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: true }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-18', context: { otherScenarioGateSatisfied: false }, units: [{ outcome: 'SUSPICIOUS', suspicionBasisComplete: true }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-19', units: [{ outcome: 'EXPLAINED', assessmentValid: false }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-20', context: { caseStatus: 'DONE' }, units: [{ outcome: 'EXPLAINED' }], expected: { canExclude: false, canConfirm: false } },
  { id: 'DG-21', units: [], expected: { canExclude: false, canConfirm: false } },
]

const contextDefaults = {
  caseStatus: 'HOLD', scopeEnumerated: true, adoptedFactsUsable: true, reviewBasisCurrent: true,
  policyApplicable: true, reviewerIndependent: true, otherScenarioGateSatisfied: true,
  hasOpenDecisionSupportEdd: false, obligationTransferReady: false, continuationPlanReady: false,
}
const unitDefaults = {
  assessmentValid: true, criticalUnknown: false, suspicionBasisComplete: false,
  unresolvedDisclosed: false, followupRequired: false,
}

function contextFrom(overrides: Record<string, unknown> | undefined): CaseDecisionContext {
  const merged = { ...contextDefaults, ...(overrides ?? {}) } as Record<string, unknown>
  return {
    caseStatusHold: merged.caseStatus === 'HOLD',
    scopeEnumerated: Boolean(merged.scopeEnumerated),
    adoptedFactsUsable: Boolean(merged.adoptedFactsUsable),
    reviewBasisCurrent: Boolean(merged.reviewBasisCurrent),
    policyApplicable: Boolean(merged.policyApplicable),
    reviewerIndependent: Boolean(merged.reviewerIndependent),
    otherScenarioGateSatisfied: Boolean(merged.otherScenarioGateSatisfied),
    hasOpenDecisionSupportEdd: Boolean(merged.hasOpenDecisionSupportEdd),
    obligationTransferReady: Boolean(merged.obligationTransferReady),
    continuationPlanReady: Boolean(merged.continuationPlanReady),
  }
}

function unitsFrom(units: Array<Partial<UnitDecisionInput>>): UnitDecisionInput[] {
  return units.map((unit) => ({
    outcome: unit.outcome as UnitDecisionInput['outcome'],
    assessmentValid: unit.assessmentValid ?? unitDefaults.assessmentValid,
    criticalUnknown: unit.criticalUnknown ?? unitDefaults.criticalUnknown,
    suspicionBasisComplete: unit.suspicionBasisComplete ?? unitDefaults.suspicionBasisComplete,
    unresolvedDisclosed: unit.unresolvedDisclosed ?? unitDefaults.unresolvedDisclosed,
    followupRequired: unit.followupRequired ?? unitDefaults.followupRequired,
  }))
}

describe('决策表镜像与冻结设计样例一致（21 案例）', () => {
  const guardKeys = ['scopeEnumerated', 'adoptedFactsUsable', 'reviewBasisCurrent',
    'policyApplicable', 'reviewerIndependent', 'otherScenarioGateSatisfied'] as const
  let excludePositive = 0
  let confirmPositive = 0
  let neutral = 0

  for (const sample of designCases) {
    it(`${sample.id}`, () => {
      const context = contextFrom(sample.context)
      const units = unitsFrom(sample.units)
      const actual = evaluateDecision(context, units)
      expect(actual).toEqual(sample.expected)
      // 方向互斥
      expect(actual.canExclude && actual.canConfirm).toBe(false)
      // 顺序不敏感
      const reversed = [...units].reverse()
      expect(evaluateDecision(context, reversed)).toEqual(actual)
      // 关键门禁关闭时不得放行
      for (const key of guardKeys) {
        const broken = contextFrom({ ...sample.context, [key]: false })
        expect(evaluateDecision(broken, units)).toEqual({ canExclude: false, canConfirm: false })
      }
      if (sample.expected.canExclude) excludePositive++
      if (sample.expected.canConfirm) confirmPositive++
      if (!sample.expected.canExclude && !sample.expected.canConfirm) neutral++
    })
  }

  it('拒绝一切/放行一切的政策不得“通过”本设计集（V2-26 设计层预检）', () => {
    expect(excludePositive).toBeGreaterThan(0)
    expect(confirmPositive).toBeGreaterThan(0)
    expect(neutral).toBeGreaterThan(0)
  })
})

describe('假设汇总与冲突协议判定', () => {
  it('汇总规则：任一可疑 → CONFIRMED；全部解释 → REJECTED；否则 OPEN（§3.3）', () => {
    expect(hypothesisAggregate([])).toBe('UNRESOLVED')
    expect(hypothesisAggregate(['EXPLAINED', 'SUSPICIOUS'])).toBe('SUSPICIOUS')
    expect(hypothesisAggregate(['EXPLAINED', 'EXPLAINED'])).toBe('EXPLAINED')
    expect(hypothesisAggregate(['EXPLAINED', 'UNRESOLVED'])).toBe('UNRESOLVED')
  })

  it('仅 409 + INVESTIGATION_REVISION_CONFLICT 被视为冲突', () => {
    const conflict = { response: { status: 409, data: { code: 'INVESTIGATION_REVISION_CONFLICT' } } }
    expect(isExplanationRevisionConflict(conflict)).toBe(true)
    expect(isExplanationRevisionConflict({
      response: { status: 409, data: { code: 'WORKFLOW_STATE_CONFLICT' } },
    })).toBe(false)
    expect(isExplanationRevisionConflict({ response: { status: 412, data: {} } })).toBe(false)
  })
})
