import { parseRefundLedger, parseRefundRegistration } from './contracts'
import type { ExplanationContinuationTask } from './explanation-api'
import { api } from './http'
import type { CaseItem, ManualReview } from './models'

// ---------- 人工复核 ----------
export async function listPendingReviews(): Promise<CaseItem[]> {
  return (await api.get('/reviews/pending')).data
}

export async function listCaseReviews(caseId: number): Promise<ManualReview[]> {
  return (await api.get(`/reviews/${caseId}`)).data
}

export async function submitReview(
  caseId: number,
  body: {
    reviewerRiskLevel: string
    decision: string
    reasonCode: string
    comment: string
    expectedReviewRevision: number
    requiredItems?: string[]
    dueAt?: string
    assignedTo?: string
    assignedUnit?: string
    /** v2 解释核验：最终复核依据令牌（GET review-basis 取得）；v1 案件可不带。 */
    reviewBasisToken?: string
    /** v2：义务接续计划（可疑 + 已披露未知路径必须提供）。 */
    continuationTasks?: ExplanationContinuationTask[]
  },
): Promise<ManualReview> {
  return (await api.post(`/reviews/${caseId}`, body)).data
}

/** 复核预检（只读模拟，v3 闭环方案 §6）：不创建任务，返回计划覆盖差异与决策表快照。 */
export interface ReviewPrecheckResult {
  caseId: number
  caseStatus: string
  caseFactsEpoch: number
  reviewRevision: number
  decision: string | null
  tokenCurrent: boolean
  tokenProblem?: string
  continuationPlanChecks: {
    originRequestId: number | null
    problem: string | null
    originRound?: number
    assignedTo?: string
    dueAt?: string
    completionStandardPresent: boolean
    standardProblem?: string
  }[]
  uncoveredDecisionSupportTasks: number[]
  canExclude?: boolean
  canConfirm?: boolean
  excludeBlockers?: string[]
  confirmBlockers?: string[]
}

export async function reviewPrecheck(
  caseId: number,
  body: {
    decision?: string
    reviewBasisToken?: string
    continuationTasks?: ExplanationContinuationTask[]
  },
): Promise<ReviewPrecheckResult> {
  return (await api.post(`/reviews/${caseId}/prechecks`, body)).data
}

// ==================== 退款事件（v4 计划 §7.1 / G3-2） ====================

export interface RefundAllocationInput {
  originalTransactionId: string
  originalAllocationKey: string
  allocatedAmount: string
  returnedObligationRef?: string
}

export interface RefundRegistrationInput {
  sourceSystem: string
  externalEventId: string
  eventStatus?: 'REQUESTED' | 'POSTED' | 'REVERSED'
  payerSubject: string
  payeeSubject: string
  payeeAccountRef?: string
  amount: string
  currency: string
  effectiveAt: string
  allocations?: RefundAllocationInput[]
}

export interface RefundRegistrationResult {
  eventId: number
  idempotentReplay: boolean
  unallocatedAmount: string
  currency: string
  eventStatus: string
}

export async function registerRefundEvent(
  caseId: number,
  body: RefundRegistrationInput,
): Promise<RefundRegistrationResult> {
  return parseRefundRegistration<RefundRegistrationResult>(
    (await api.post(`/cases/${caseId}/refund-events`, body)).data,
  )
}

export interface RefundLedgerResult {
  originalByTransaction: Record<string, string>
  refundedByTransaction: Record<string, string>
  pendingRefundByTransaction: Record<string, string>
  totalOriginal: string
  totalRefunded: string
  totalPendingRefund: string
  totalRetained: string
  currency: string
  overAllocations: Record<string, string>
}

export async function getRefundLedger(caseId: number, originalTransactionIds: string[]): Promise<RefundLedgerResult> {
  return parseRefundLedger<RefundLedgerResult>(
    (
      await api.get(`/cases/${caseId}/refund-events/ledger`, {
        params: { originalTransactionIds },
      })
    ).data,
  )
}

export interface RefundAdmissibilityResult {
  admissible: boolean
  blockerCode: string | null
  explanation: string
}

export type RefundRecipientAuthority = 'ORIGINAL_PAYER_VERIFIED' | 'BUYER_VERIFIED' | 'UNRESOLVED' | 'CONTRADICTED'
export type RefundCommercialReason = 'VERIFIED' | 'UNVERIFIED' | 'MISSING'

export interface RefundAuthorityAssessmentInput {
  recipientAuthority: RefundRecipientAuthority
  commercialReason: RefundCommercialReason
}

export async function assessRefundAuthority(
  caseId: number,
  body: RefundAuthorityAssessmentInput,
): Promise<RefundAdmissibilityResult> {
  return (await api.post(`/cases/${caseId}/refund-events/authority-assessment`, body)).data
}

export async function reverseRefundEvent(
  caseId: number,
  body: { sourceSystem: string; originalExternalEventId: string; reversalExternalEventId: string },
) {
  return (await api.post(`/cases/${caseId}/refund-events/reversals`, body)).data
}
