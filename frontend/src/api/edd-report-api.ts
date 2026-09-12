import type { EnhancedDueDiligenceEvidenceSubmission, EnhancedDueDiligenceRequest } from './case-api'
import { api } from './http'

export async function reviewStats(): Promise<{
  reviewedCount: number
  agreementRate: number
  confirmedSuspiciousCount: number
  falsePositiveCount: number
  eddRequestedCount: number
}> {
  return (await api.get('/reviews/stats')).data
}

export async function listEnhancedDueDiligence(caseId: number): Promise<EnhancedDueDiligenceRequest[]> {
  return (await api.get(`/cases/${caseId}/edd`)).data
}

export async function listPendingEnhancedDueDiligence(all = false): Promise<EnhancedDueDiligenceRequest[]> {
  return (await api.get('/edd/tasks', { params: { all } })).data
}

export async function listEnhancedDueDiligenceAssignees(): Promise<{ username: string; role: string }[]> {
  return (await api.get('/edd/assignees')).data
}

export async function submitEnhancedDueDiligence(
  caseId: number,
  requestId: number,
  body: {
    expectedRevision: number
    responseSummary: string
    evidenceItems: EnhancedDueDiligenceEvidenceSubmission[]
  },
): Promise<EnhancedDueDiligenceRequest> {
  return (await api.post(`/cases/${caseId}/edd/${requestId}/submit`, body)).data
}

export async function cancelEnhancedDueDiligence(
  caseId: number,
  requestId: number,
  body: { expectedRevision: number; reason: string },
): Promise<EnhancedDueDiligenceRequest> {
  return (await api.post(`/cases/${caseId}/edd/${requestId}/cancel`, body)).data
}

export type SuspiciousTransactionReportStatus = 'PENDING_SUBMISSION' | 'SUBMITTED' | 'RETURNED_FOR_CORRECTION'

export interface SuspiciousTransactionReport {
  id: number
  caseId: number
  reviewId: number
  status: SuspiciousTransactionReportStatus
  reportReason: string
  createdBy: string
  revision: number
  externalReference: string | null
  submittedBy: string | null
  submittedAt: string | null
  returnedBy: string | null
  returnedAt: string | null
  returnReason: string | null
  createdAt: string
  updatedAt: string
}

export async function listPendingSuspiciousReports(): Promise<SuspiciousTransactionReport[]> {
  return (await api.get('/reports/pending')).data
}

export async function getSuspiciousTransactionReport(caseId: number): Promise<SuspiciousTransactionReport> {
  return (await api.get(`/reports/${caseId}`)).data
}

export async function submitSuspiciousReport(
  caseId: number,
  expectedRevision: number,
  externalReference: string,
): Promise<SuspiciousTransactionReport> {
  return (await api.post(`/reports/${caseId}/submit`, { expectedRevision, externalReference })).data
}

export async function returnSuspiciousReport(
  caseId: number,
  expectedRevision: number,
  reason: string,
): Promise<SuspiciousTransactionReport> {
  return (await api.post(`/reports/${caseId}/return`, { expectedRevision, reason })).data
}
