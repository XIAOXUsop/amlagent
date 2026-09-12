/**
 * 解释核验工作区的前端口径（v2 计划 §7.2/§13/§14）。
 * 与后端 ExplanationDecisionRules 保持同一决策表；页面只展示 allowedActions，
 * 最终校验始终由后端在案件锁内重新执行。
 */

export type ExplanationOutcome = 'EXPLAINED' | 'SUSPICIOUS' | 'UNRESOLVED'

export type QuestionAssessment = 'SATISFIED' | 'NOT_SATISFIED' | 'UNKNOWN' | 'NOT_APPLICABLE'

export type IssueSeverity = 'INTEGRITY_BLOCKER' | 'DECISION_CRITICAL' | 'CONTEXT_GAP' | 'FUTURE_OBLIGATION'

export type IssueDisposition = 'OPEN' | 'RESOLVED_WITH_EVIDENCE' | 'NOT_RELEVANT_WITH_REASON' | 'DISCLOSED_UNRESOLVED'

/** 案件层前提（与后端 CaseContext / 设计样例 contextDefaults 对应）。 */
export interface CaseDecisionContext {
  caseStatusHold: boolean
  scopeEnumerated: boolean
  adoptedFactsUsable: boolean
  reviewBasisCurrent: boolean
  policyApplicable: boolean
  reviewerIndependent: boolean
  otherScenarioGateSatisfied: boolean
  hasOpenDecisionSupportEdd: boolean
  obligationTransferReady: boolean
  continuationPlanReady: boolean
}

/** 预警单元评估输入（与设计样例 unitDefaults 对应）。 */
export interface UnitDecisionInput {
  outcome: ExplanationOutcome
  assessmentValid: boolean
  criticalUnknown: boolean
  suspicionBasisComplete: boolean
  unresolvedDisclosed: boolean
  followupRequired: boolean
}

export interface Decision {
  canExclude: boolean
  canConfirm: boolean
}

/** 决策表（§7.2）：与 ExplanationDecisionRules.evaluate 同一口径。 */
export function evaluateDecision(context: CaseDecisionContext, units: UnitDecisionInput[]): Decision {
  const eligible =
    context.caseStatusHold &&
    context.scopeEnumerated &&
    context.adoptedFactsUsable &&
    context.reviewBasisCurrent &&
    context.policyApplicable &&
    context.reviewerIndependent &&
    context.otherScenarioGateSatisfied &&
    units.length > 0 &&
    units.every((unit) => unit.assessmentValid)
  const taskGate =
    !context.hasOpenDecisionSupportEdd || (context.obligationTransferReady && context.continuationPlanReady)
  const followupReady = (unit: UnitDecisionInput) =>
    !(unit.followupRequired || unit.outcome === 'UNRESOLVED' || unit.criticalUnknown) || context.continuationPlanReady
  const explained = (unit: UnitDecisionInput) =>
    unit.outcome === 'EXPLAINED' && !unit.criticalUnknown && followupReady(unit)
  const evaluableForSuspicion = (unit: UnitDecisionInput) => {
    if (unit.outcome === 'EXPLAINED') return explained(unit)
    if (unit.outcome === 'SUSPICIOUS') {
      return unit.suspicionBasisComplete && followupReady(unit) && (!unit.criticalUnknown || unit.unresolvedDisclosed)
    }
    return unit.unresolvedDisclosed && followupReady(unit)
  }
  return {
    canExclude: Boolean(eligible && taskGate && units.length > 0 && units.every(explained)),
    canConfirm: Boolean(
      eligible &&
      taskGate &&
      units.some((unit) => unit.outcome === 'SUSPICIOUS' && unit.suspicionBasisComplete) &&
      units.every(evaluableForSuspicion),
    ),
  }
}

/** 假设汇总规则（§3.3）：任一 SUSPICIOUS → CONFIRMED；全部 EXPLAINED → REJECTED；否则 OPEN。 */
export function hypothesisAggregate(outcomes: ExplanationOutcome[]): ExplanationOutcome {
  if (outcomes.length === 0) return 'UNRESOLVED'
  if (outcomes.some((outcome) => outcome === 'SUSPICIOUS')) return 'SUSPICIOUS'
  if (outcomes.every((outcome) => outcome === 'EXPLAINED')) return 'EXPLAINED'
  return 'UNRESOLVED'
}

export const outcomeText: Record<ExplanationOutcome, string> = {
  EXPLAINED: '解释成立',
  SUSPICIOUS: '存在疑点',
  UNRESOLVED: '未决',
}

export const outcomeTagType: Record<ExplanationOutcome, 'success' | 'danger' | 'warning'> = {
  EXPLAINED: 'success',
  SUSPICIOUS: 'danger',
  UNRESOLVED: 'warning',
}

export const assessmentText: Record<QuestionAssessment, string> = {
  SATISFIED: '已满足',
  NOT_SATISFIED: '未满足',
  UNKNOWN: '未知',
  NOT_APPLICABLE: '不适用',
}

export const severityText: Record<IssueSeverity, string> = {
  INTEGRITY_BLOCKER: '完整性/身份错误',
  DECISION_CRITICAL: '关键业务矛盾',
  CONTEXT_GAP: '非关键缺口',
  FUTURE_OBLIGATION: '尚未到期事项',
}

export const dispositionText: Record<IssueDisposition, string> = {
  OPEN: '待处理',
  RESOLVED_WITH_EVIDENCE: '已凭证据解决',
  NOT_RELEVANT_WITH_REASON: '已说明不相关',
  DISCLOSED_UNRESOLVED: '已披露未解决',
}

export const QUESTION_CODES = ['Q1', 'Q2', 'Q3', 'Q4', 'Q5', 'Q6'] as const

/** 配方口径（§4/§5）；与后端 ExplanationPolicyCatalog.questionFocus 一致。 */
export const policyQuestionFocus: Record<string, Record<string, string>> = {
  GOODS_SETTLED_V1: {
    Q1: '企业角色、货物、结算规模与日常经营是否相符',
    Q2: '付款人与买方是否一致；订单和收款是否对应',
    Q3: '收款人、采购及交付是否对应',
    Q4: '每笔命中交易怎样对应订单、金额和期间',
    Q5: '为什么快速结算，收支差额属于什么',
    Q6: '重要差异是否已解释、未知是否妨碍本次判断',
  },
  GOODS_PREPAY_V1: {
    Q1: '为什么需要预付，账期与采购模式是否相符',
    Q2: '收入是订金、货款还是其他性质，是否确有依据',
    Q3: '预付条款、收款授权、交期是否明确',
    Q4: '本次预付占订单金额多少，余款/未来履约节点是什么',
    Q5: '为什么需要同日预付，预付规模及风险如何被解释',
    Q6: '未到期事项与已逾期事项是否区分；后续谁查什么',
  },
}

/**
 * 依从性守卫：识别调查版本冲突协议（与 utils/investigation.ts 同一判定），
 * 复用统一的冲突提示与草稿保留行为。
 */
export function isExplanationRevisionConflict(error: unknown): boolean {
  const response = (error as { response?: { status?: number; data?: { code?: string } } } | null)?.response
  return response?.status === 409 && response?.data?.code === 'INVESTIGATION_REVISION_CONFLICT'
}
