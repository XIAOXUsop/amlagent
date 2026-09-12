// 认证使用 HttpOnly Cookie，不向 localStorage 写入长期 JWT

export interface Customer {
  id: string
  name: string
  type: string
  industry: string
  region: string
  regCapital: string
}

export interface CaseItem {
  id: number
  customerId: string
  customerName: string
  alertRule: string
  status: string
  riskLevel: string | null
  rawRiskLevel: string | null
  reportJson: string | null
  summary: string | null
  reportSource: string | null
  snapshotId: string | null
  modelProvider: string | null
  modelName: string | null
  modelFallback: boolean
  executionVersion: number
  reviewRevision: number
  investigationContractVersion: number
  reviewDisposition: string | null
  reviewReasonCode: string | null
  reviewedAt: string | null
  retryCount: number
  failureCode: string | null
  failureMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface CaseLog {
  id: number
  stage: string
  content: string
}

export interface WorkflowEvent {
  caseId: number
  stage: string
  content: string
}

export interface DueDiligenceReport {
  customerId: string
  customerName: string
  riskLevel: string
  transactionProfile: string
  corporateProfile: string
  sanctions: string[]
  legalBasis: string[]
  riskPoints: string[]
  conclusion: string
  evidenceChain: string[]
  manualReviewRequired: boolean
  findingCodes: string[]
  actionCodes: string[]
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface ManualReview {
  id: number
  caseId: number
  reviewerId: string
  agentRiskLevel: string
  guardrailRiskLevel: string
  reviewerRiskLevel: string
  decision: string
  reasonCode: string
  comment: string
  reviewRevision: number
  caseStatusBefore: string
  caseStatusAfter: string
  createdAt: string
  completedAt: string
}
