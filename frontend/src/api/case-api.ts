import { api } from './http'
import type { CaseItem, Customer, Page } from './models'

// ---------- 工单 ----------
export async function listCases(page = 0, size = 20): Promise<Page<CaseItem>> {
  return (await api.get('/cases', { params: { page, size } })).data
}

export interface CaseStats {
  total: number
  pending: number
  running: number
  hold: number
  reportPending: number
  done: number
  failed: number
}

/** 全量工单状态统计（态势概览，非当前页局部统计） */
export async function listCaseStats(): Promise<CaseStats> {
  return (await api.get('/cases/stats')).data
}

export async function listCustomers(): Promise<Customer[]> {
  return (await api.get('/cases/customers')).data
}

// ---------- 客户/人员管理（ADMIN） ----------
export interface CustomerAdminItem {
  id: number
  customerNo: string
  name: string
  idCardMasked: string
  type: string | null
  industry: string | null
  region: string | null
  regCapital: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export type AlertStatus = 'NEW' | 'LINKED' | 'DUPLICATE'
export type InvestigationScenarioCode =
  | 'STRUCTURING'
  | 'RAPID_MOVEMENT'
  | 'CROSS_BORDER_ANOMALY'
  | 'PROFILE_MISMATCH'
  | 'COMPLEX_OWNERSHIP'
  | 'SANCTIONS_WATCHLIST'
export type HypothesisStatus = 'OPEN' | 'CONFIRMED' | 'REJECTED'
export type EvidenceStance = 'SUPPORTS' | 'CONTRADICTS'
export type InvestigationEvidenceType =
  | 'TRANSACTION'
  | 'CUSTOMER_PROFILE'
  | 'BENEFICIAL_OWNERSHIP'
  | 'SANCTIONS_SCREENING'
  | 'DOCUMENT'
  | 'EXTERNAL_DATA'
  | 'LEGAL'
export type AlertCoverageConclusion = 'PENDING' | 'SUSPICIOUS' | 'EXPLAINED'

export interface AmlAlert {
  id: number
  externalAlertId: string
  customerId: string
  ruleCode: string
  scenarioCode: InvestigationScenarioCode
  hitReason: string
  occurredAt: string
  status: AlertStatus
  caseId: number | null
  revision: number
  resolutionReason: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface InvestigationEvidence {
  id: number
  hypothesisId: number
  evidenceType: InvestigationEvidenceType
  evidenceReference: string
  stance: EvidenceStance
  findingSummary: string
  createdBy: string
  createdAt: string
}

export interface InvestigationHypothesis {
  id: number
  caseId: number
  scenarioCode: InvestigationScenarioCode
  hypothesisCode: string
  title: string
  investigationQuestion: string
  requiredEvidenceTypes: InvestigationEvidenceType[]
  status: HypothesisStatus
  rationale: string | null
  revision: number
  createdBy: string
  updatedBy: string | null
  createdAt: string
  updatedAt: string
  evidence: InvestigationEvidence[]
}

export interface AlertCoverage {
  id: number
  alertId: number
  caseId: number
  hypothesisId: number | null
  /** 覆盖决定形成时锁定的假设版本；null 表示存量数据，门禁会要求重新确认。 */
  hypothesisRevision: number | null
  conclusion: AlertCoverageConclusion
  analysisSummary: string | null
  revision: number
  updatedBy: string | null
  updatedAt: string
}

export interface CaseInvestigation {
  contractVersion: number
  alerts: AmlAlert[]
  hypotheses: InvestigationHypothesis[]
  coverage: AlertCoverage[]
  /** 两种结案路径（确认可疑 / 排除预警）至少一条不被阻断 */
  readyForFinalReview: boolean
  /** 与具体决定无关的调查完成度阻断（供分析员待办与详情展示） */
  generalBlockers: string[]
  confirmSuspiciousBlockers: string[]
  excludeFalsePositiveBlockers: string[]
}

export interface InvestigationPlaybook {
  code: InvestigationScenarioCode
  name: string
  defaultHypothesisCode: string
  defaultHypothesisTitle: string
  investigationQuestion: string
  requiredEvidenceTypes: InvestigationEvidenceType[]
  escalationFocus: string
}

export interface TransactionWindowView {
  asOfTime: string
  sourceSystem: string
  sourceVersion: string
  windows: {
    days: number
    transactionCount: number
    currencyBreakdown: {
      currency: string
      totalAmount: string
      incomingAmount: string
      outgoingAmount: string
      crossBorderAmount: string
    }[]
    crossBorderCount: number
    nightCount: number
    topCounterparties: {
      counterparty: string
      transactionCount: number
      amounts: { currency: string; amount: string }[]
    }[]
  }[]
}

export type EnhancedDueDiligenceStatus = 'OPEN' | 'SUBMITTED' | 'RESOLVED' | 'CANCELLED'

export type CasePriority = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'NORMAL'
export type OperationPhase = 'INVESTIGATION' | 'REVIEW' | 'ENHANCED_DUE_DILIGENCE' | 'REPORTING' | 'COMPLETED'
export interface CaseOperationsView {
  caseId: number
  customerId: string
  customerName: string
  caseStatus: CaseItem['status']
  priority: CasePriority
  priorityScore: number
  priorityReasons: string[]
  priorityPolicy: string
  phase: OperationPhase
  responsibleRole: string
  assignedTo: string | null
  assignedUnit: string | null
  clockStartedAt: string
  dueAt: string | null
  overdue: boolean
  minutesRemaining: number
  slaPolicy: string
  calculatedAt: string
}

export async function listCaseOperations(params?: {
  overdueOnly?: boolean
  priority?: CasePriority | ''
  phase?: OperationPhase | ''
}): Promise<CaseOperationsView[]> {
  return (
    await api.get('/case-operations', {
      params: {
        overdueOnly: params?.overdueOnly ?? undefined,
        priority: params?.priority === '' ? undefined : params?.priority,
        phase: params?.phase === '' ? undefined : params?.phase,
      },
    })
  ).data
}

export async function getCaseOperations(caseId: number): Promise<CaseOperationsView> {
  return (await api.get(`/case-operations/${caseId}`)).data
}

export interface EnhancedDueDiligenceEvidenceSubmission {
  requiredItemCode: string
  sourceSystem: string
  sourceReference: string
  contentSha256: string
}

export interface EnhancedDueDiligenceEvidence extends EnhancedDueDiligenceEvidenceSubmission {
  id: number
  evidenceId: string
  capturedBy: string
  capturedAt: string
}

export interface EnhancedDueDiligenceRequest {
  id: number
  caseId: number
  roundNo: number
  reasonCode: string
  requiredItems: string[]
  requestedBy: string
  requestedAt: string
  assignedTo: string | null
  assignedUnit: string | null
  dueAt: string
  status: EnhancedDueDiligenceStatus
  overdue: boolean
  revision: number
  responseSummary: string | null
  evidenceReferences: string[]
  respondedBy: string | null
  respondedAt: string | null
  resolvedAt: string | null
  cancelledBy: string | null
  cancelledAt: string | null
  cancellationReason: string | null
  evidenceItems: EnhancedDueDiligenceEvidence[]
}

export interface CustomerEditPayload {
  name?: string
  idCard?: string
  type?: string | null
  industry?: string | null
  region?: string | null
  regCapital?: string | null
  status?: string
}

export interface CustomerImportResult {
  total: number
  success: number
  failed: number
  errors: string[]
}

export async function listAdminCustomers(page = 0, size = 10, keyword = ''): Promise<Page<CustomerAdminItem>> {
  return (await api.get('/admin/customers', { params: { page, size, keyword: keyword || undefined } })).data
}

export async function getAdminCustomer(id: number): Promise<CustomerAdminItem> {
  return (await api.get(`/admin/customers/${id}`)).data
}

export async function createAdminCustomer(payload: CustomerEditPayload): Promise<CustomerAdminItem> {
  return (await api.post('/admin/customers', payload)).data
}

export async function updateAdminCustomer(id: number, payload: CustomerEditPayload): Promise<CustomerAdminItem> {
  return (await api.put(`/admin/customers/${id}`, payload)).data
}

export async function deleteAdminCustomer(id: number): Promise<void> {
  await api.delete(`/admin/customers/${id}`)
}

export async function setCustomerStatus(id: number, status: string): Promise<CustomerAdminItem> {
  return (await api.put(`/admin/customers/${id}/status`, null, { params: { status } })).data
}

export async function importCustomers(file: File): Promise<CustomerImportResult> {
  const fd = new FormData()
  fd.append('file', file)
  return (await api.post('/admin/customers/import', fd)).data
}
