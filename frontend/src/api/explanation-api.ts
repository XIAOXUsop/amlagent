import { api } from './http'

// ==================== 解释核验工作区（v2 计划 §13） ====================

export interface ExplanationUnitView {
  unitId: number
  alertId: number
  externalAlertId: string | null
  hypothesisId: number | null
  policyCode: string | null
  draftRevision: number
  draftJson: string | null
  hasCurrentSubmission: boolean
  currentSubmissionId: number | null
  currentOutcome: 'EXPLAINED' | 'SUSPICIOUS' | 'UNRESOLVED' | null
  criticalUnknown: boolean
  followupRequired: boolean
  blockers: string[]
}

export interface ExplanationIssueView {
  issueId: number
  unitId: number | null
  issueKey: string
  severity: 'INTEGRITY_BLOCKER' | 'DECISION_CRITICAL' | 'CONTEXT_GAP' | 'FUTURE_OBLIGATION'
  questionCode: string | null
  transactionIds: string | null
  description: string
  disposition: 'OPEN' | 'RESOLVED_WITH_EVIDENCE' | 'NOT_RELEVANT_WITH_REASON' | 'DISCLOSED_UNRESOLVED'
  dispositionReason: string | null
  resolvedBy: string | null
  confirmedBy: string | null
  revision: number
}

export interface ExplanationEvidenceView {
  artifactVersionId: number
  artifactKey: string
  version: number
  sourceSystem: string
  sourceReference: string
  contentSha256: string
  claimedSha256: string | null
  availability: string
  integrityStatus: string
  capturedBy: string
  capturedAt: string
}

export interface ExplanationWorkspaceView {
  caseId: number
  contractVersion: number
  caseFactsEpoch: number
  stale: boolean
  units: ExplanationUnitView[]
  issues: ExplanationIssueView[]
  artifacts: ExplanationEvidenceView[]
  generalBlockers: string[]
  canExclude: boolean
  canConfirm: boolean
  confirmBlockers: string[]
  excludeBlockers: string[]
}

export interface ExplanationSubmissionResult {
  submissionId: number
  unitId: number
  submissionNo: number
  outcome: 'EXPLAINED' | 'SUSPICIOUS' | 'UNRESOLVED'
  state: 'CURRENT' | 'WITHDRAWN' | 'STALE' | 'SUPERSEDED'
  hypothesisAggregate: 'EXPLAINED' | 'SUSPICIOUS' | 'UNRESOLVED'
  reviewBasisToken: string
  messages: string[]
}

export interface ExplanationReviewBasisView {
  caseId: number
  caseFactsEpoch: number
  reviewBasisToken: string
  reviewerIndependent: boolean
  canExclude: boolean
  canConfirm: boolean
  confirmBlockers: string[]
  excludeBlockers: string[]
  adoptedUnits: ExplanationUnitView[]
  openIssues: ExplanationIssueView[]
  unitOutcomes: Record<string, string>
}

export interface ExplanationContinuationTask {
  originRequestId: number | null
  assignedTo: string
  assignedUnit: string
  dueAt: string
  requiredItems?: string[]
  completionStandard: string
  issueBindingsJson: string | null
}

export async function getExplanationWorkspace(caseId: number): Promise<ExplanationWorkspaceView> {
  return (await api.get(`/cases/${caseId}/investigation/explanation-workspace`)).data
}

export async function captureExplanationEvidence(
  caseId: number,
  body: { sourceSystem: string; sourceReference: string },
): Promise<ExplanationEvidenceView> {
  return (await api.post(`/cases/${caseId}/investigation/evidence/captures`, body)).data
}

export async function recordEvidenceVerification(
  caseId: number,
  versionId: number,
  body: {
    method: string
    observedFacts: string
    limitations?: string
    result: string
    /** FR-01：核验对象（Q1~Q6 或 Claim 事实键）；空为材料级通用核验。 */
    subjectFactKey?: string
  },
) {
  return (await api.post(`/cases/${caseId}/investigation/evidence/${versionId}/verifications`, body)).data
}

export async function saveExplanationDraft(
  caseId: number,
  unitId: number,
  body: { expectedDraftRevision: number; draftJson: string },
): Promise<ExplanationUnitView> {
  return (await api.put(`/cases/${caseId}/investigation/units/${unitId}/draft`, body)).data
}

export async function submitExplanationUnit(
  caseId: number,
  unitId: number,
  body: { expectedDraftRevision: number; reviewBasisToken: string; idempotencyKey: string },
): Promise<ExplanationSubmissionResult> {
  return (await api.post(`/cases/${caseId}/investigation/units/${unitId}/submissions`, body)).data
}

export async function amendExplanationUnit(
  caseId: number,
  unitId: number,
  body: { currentSubmissionId: number },
): Promise<ExplanationUnitView> {
  return (await api.post(`/cases/${caseId}/investigation/units/${unitId}/amendments`, body)).data
}

export async function disposeExplanationIssue(
  caseId: number,
  issueId: number,
  body: { expectedRevision: number; disposition: string; reason: string; evidenceReference?: string },
): Promise<ExplanationIssueView> {
  return (await api.post(`/cases/${caseId}/investigation/issues/${issueId}/dispositions`, body)).data
}

/** 降级提案第一步（A5-04）：分析员提交拟议降级，等待独立复核人确认。 */
export async function proposeIssueDowngrade(
  caseId: number,
  issueId: number,
  body: { expectedRevision: number; downgradeTo: string; reason: string; evidenceReference?: string },
) {
  return (await api.post(`/cases/${caseId}/investigation/issues/${issueId}/downgrade-proposals`, body)).data
}

/** 降级确认第二步（A5-04）：另一位 REVIEWER/ADMIN 独立确认。 */
export async function confirmIssueDowngrade(
  caseId: number,
  proposalId: number,
  body: { expectedProposalRevision: number; expectedIssueRevision: number; confirmNote?: string },
) {
  return (await api.post(`/cases/${caseId}/investigation/downgrade-proposals/${proposalId}/confirmations`, body)).data
}

/** 拒绝降级提案。 */
export async function rejectIssueDowngrade(
  caseId: number,
  proposalId: number,
  body: { expectedProposalRevision: number; rejectedReason: string },
) {
  return (await api.post(`/cases/${caseId}/investigation/downgrade-proposals/${proposalId}/rejections`, body)).data
}

/** 定向核验建议（v3 §7）：下一动作按优先级排序。 */
export interface ExplanationNextActionView {
  priority: number
  category: string
  relatedClaimCode: string | null
  relatedIssueKey: string | null
  missingFact: string
  suggestedSource: string
  suggestedAction: string
  whatItCanChange: string
  alternative: string
}

export async function fetchUnitNextActions(caseId: number, unitId: number): Promise<ExplanationNextActionView[]> {
  return (await api.get(`/cases/${caseId}/investigation/units/${unitId}/next-actions`)).data
}

/** 重复补件提醒：同事实键已有 ≥2 条处置记录。 */
export async function checkRepeatedEvidence(
  caseId: number,
  unitId: number,
  factKey: string,
): Promise<{ repeated: boolean }> {
  return (
    await api.get(`/cases/${caseId}/investigation/units/${unitId}/repeated-evidence`, {
      params: { factKey },
    })
  ).data
}

export async function proposeExplanationEdd(
  caseId: number,
  body: { requiredItems: string[]; unitLabel?: string; issueBindingsJson?: string },
) {
  return (await api.post(`/cases/${caseId}/investigation/edd-proposals`, body)).data
}

export async function getExplanationReviewBasis(caseId: number): Promise<ExplanationReviewBasisView> {
  return (await api.get(`/cases/${caseId}/investigation/review-basis`)).data
}

export async function completeContinuingTask(
  caseId: number,
  requestId: number,
  body: { expectedRevision: number; resolutionReason: string },
) {
  return (await api.post(`/cases/${caseId}/investigation/edd-tasks/${requestId}/completion`, body)).data
}
