import { api } from './http'
import type {
  AmlAlert,
  AlertCoverage,
  CaseInvestigation,
  EvidenceStance,
  InvestigationEvidence,
  InvestigationEvidenceType,
  InvestigationHypothesis,
  InvestigationPlaybook,
  InvestigationScenarioCode,
  TransactionWindowView,
} from './case-api'
import type { CaseItem, CaseLog, DueDiligenceReport, WorkflowEvent } from './models'
import { parseDueDiligenceReport, parseSseToken, parseWorkflowEvent } from './response-contracts'

export interface CaseStartOptions {
  /** true：创建后立即入队开始尽调；false：仅创建案件（可继续归并/拆分），稍后显式“开始调查”。 */
  autoProcess: boolean
  /** v2 试点显式开启（§17.2）：true 时契约版本=2，案件进入解释核验工作区。 */
  enableExplanationPolicy?: boolean
}

/** 创建预警工单；autoProcess 必须显式传入，防止调用点遗漏后悄悄恢复立即入队。 */
export async function createCase(customerId: string, alertRule: string, options: CaseStartOptions): Promise<CaseItem> {
  return (await api.post('/cases', { customerId, alertRule, autoProcess: options.autoProcess })).data
}

export async function listAlertInbox(): Promise<AmlAlert[]> {
  return (await api.get('/alerts')).data
}

export async function createAmlAlert(body: {
  externalAlertId: string
  customerId: string
  ruleCode: string
  scenarioCode: InvestigationScenarioCode
  hitReason: string
  occurredAt?: string
}): Promise<AmlAlert> {
  return (await api.post('/alerts', body)).data
}

export async function listAlertCandidateCases(alertId: number): Promise<CaseItem[]> {
  return (await api.get(`/alerts/${alertId}/candidate-cases`)).data
}

export async function createCaseFromAlert(
  alertId: number,
  expectedRevision: number,
  options: CaseStartOptions,
): Promise<CaseItem> {
  return (
    await api.post(`/alerts/${alertId}/create-case`, {
      expectedRevision,
      autoProcess: options.autoProcess,
      ...(options.enableExplanationPolicy ? { enableExplanationPolicy: true } : {}),
    })
  ).data
}

export async function linkAlertToCase(
  alertId: number,
  caseId: number,
  expectedRevision: number,
  reason: string,
): Promise<AmlAlert> {
  return (await api.post(`/alerts/${alertId}/link`, { caseId, expectedRevision, reason })).data
}

export async function closeDuplicateAlert(
  alertId: number,
  expectedRevision: number,
  reason: string,
): Promise<AmlAlert> {
  return (await api.post(`/alerts/${alertId}/duplicate`, { expectedRevision, reason })).data
}

export async function splitAlertToNewCase(
  alertId: number,
  expectedRevision: number,
  reason: string,
  options: CaseStartOptions,
): Promise<CaseItem> {
  return (
    await api.post(`/alerts/${alertId}/split`, {
      expectedRevision,
      reason,
      autoProcess: options.autoProcess,
    })
  ).data
}

export async function listCaseAlerts(caseId: number): Promise<AmlAlert[]> {
  return (await api.get(`/cases/${caseId}/alerts`)).data
}

export async function getCaseInvestigation(caseId: number): Promise<CaseInvestigation> {
  return (await api.get(`/cases/${caseId}/investigation`)).data
}

export async function listInvestigationPlaybooks(caseId: number): Promise<InvestigationPlaybook[]> {
  return (await api.get(`/cases/${caseId}/investigation/playbooks`)).data
}

export async function getTransactionWindows(caseId: number): Promise<TransactionWindowView> {
  return (await api.get(`/cases/${caseId}/investigation/transaction-windows`)).data
}

export async function addInvestigationEvidence(
  caseId: number,
  hypothesisId: number,
  body: {
    evidenceType: InvestigationEvidenceType
    evidenceReference: string
    stance: EvidenceStance
    findingSummary: string
  },
): Promise<InvestigationEvidence> {
  return (await api.post(`/cases/${caseId}/investigation/hypotheses/${hypothesisId}/evidence`, body)).data
}

export async function updateInvestigationHypothesis(
  caseId: number,
  hypothesisId: number,
  body: { expectedRevision: number; status: 'CONFIRMED' | 'REJECTED'; rationale: string },
): Promise<InvestigationHypothesis> {
  return (await api.put(`/cases/${caseId}/investigation/hypotheses/${hypothesisId}`, body)).data
}

export async function updateAlertCoverage(
  caseId: number,
  alertId: number,
  body: {
    expectedRevision: number
    hypothesisId: number
    /** 覆盖所关联假设的当前版本；缺失或过期会得到明确错误（409） */
    expectedHypothesisRevision: number
    conclusion: 'SUSPICIOUS' | 'EXPLAINED'
    analysisSummary: string
  },
): Promise<AlertCoverage> {
  return (await api.put(`/cases/${caseId}/investigation/alerts/${alertId}/coverage`, body)).data
}

export async function processCase(id: number): Promise<CaseItem> {
  return (await api.post(`/cases/${id}/process`)).data
}

export async function retryCase(id: number): Promise<CaseItem> {
  return (await api.post(`/cases/${id}/retry`)).data
}

export async function getCase(id: number): Promise<CaseItem> {
  return (await api.get(`/cases/${id}`)).data
}

export async function listLogs(id: number): Promise<CaseLog[]> {
  return (await api.get(`/cases/${id}/logs`)).data
}

export interface ToolTrace {
  executionVersion: number
  sequenceNo: number
  toolName: string
  success: boolean
  argumentValid: boolean
  durationMs: number
  resultDigest: string | null
  errorCode: string | null
}

export type SanctionMatchDecision = 'CONFIRMED' | 'REVIEW_REQUIRED' | 'DISMISSED'

export interface SanctionCandidateMatch {
  candidateFingerprint: string
  candidateName: string
  identityMasked: string
  listType: string
  detail: string
  severity: number
  score: number
  algorithmDecision: SanctionMatchDecision
  decision: SanctionMatchDecision
  reasonCodes: string[]
  explanation: string
  reviewDecision: 'CONFIRM' | 'DISMISS' | 'REQUEST_MORE_INFO' | null
  reviewRevision: number
  reviewedBy: string | null
  reviewedAt: string | null
  reviewComment: string | null
}

export interface SanctionScreeningResult {
  customerId: string
  customerName: string
  status: 'CONFIRMED_MATCH' | 'REVIEW_REQUIRED' | 'NO_MATCH'
  screenedAt: string
  sourceSystem: string
  sourceVersion: string
  candidates: SanctionCandidateMatch[]
}

/** 对数据库召回候选进行身份要素评分；返回值不包含完整证件号码。 */
export async function screenSanctions(customerId: string): Promise<SanctionScreeningResult> {
  return (await api.get(`/sanctions/screen/${encodeURIComponent(customerId)}`)).data
}

export async function reviewSanctionCandidate(
  customerId: string,
  body: {
    candidateFingerprint: string
    decision: 'CONFIRM' | 'DISMISS' | 'REQUEST_MORE_INFO'
    comment: string
    expectedRevision: number
  },
): Promise<SanctionScreeningResult> {
  return (await api.post(`/sanctions/screen/${encodeURIComponent(customerId)}/review`, body)).data
}

export interface CaseDossier {
  schemaVersion: string
  classification: 'INTERNAL_CONFIDENTIAL'
  generatedAt: string
  hashAlgorithm: 'SHA-256'
  contentHash: string
  content: {
    caseSummary: Record<string, unknown>
    reportParseStatus: 'MISSING' | 'VALID' | 'INVALID'
    report: DueDiligenceReport | null
    snapshot: Record<string, unknown> | null
    workflowLogs: unknown[]
    executionCheckpoints: unknown[]
    toolTraces: unknown[]
    reviewHistory: unknown[]
    sanctionReviewHistory: unknown[]
    alerts: unknown[]
    hypotheses: unknown[]
    investigationEvidence: unknown[]
    alertCoverage: unknown[]
    operations: Record<string, unknown> | null
  }
}

/** 导出含快照元数据、流程、工具轨迹和复核历史的完整调查档案。 */
export async function getCaseDossier(id: number): Promise<CaseDossier> {
  return (await api.get(`/cases/${id}/dossier`)).data
}

/** 工具调用轨迹（脱敏，不含参数明文；按执行版本倒序返回） */
export async function listToolTraces(id: number): Promise<ToolTrace[]> {
  return (await api.get(`/cases/${id}/tools`)).data
}

/** SSE 连接状态（用于界面展示"连接中/已连接/连接断开正在重连"） */
export type SseState = 'connecting' | 'open' | 'reconnecting' | 'closed'

/**
 * 订阅工单工作流实时进度（SSE 通过 HttpOnly Cookie 认证，JWT 不进入 URL），返回取消订阅函数。
 * @param onEvent  收到 stage 事件
 * @param onToken  收到 token 事件（可选，流式摘要）
 * @param onState  连接状态回调（可选，断线时 EventSource 自动重连，前端据此提示并拉取对账）
 * @param onProtocolError 收到畸形事件时的失败回调；连接会立即关闭，避免继续消费不可信流
 */
export function subscribeCase(
  id: number,
  onEvent: (e: WorkflowEvent) => void,
  onToken?: (token: string) => void,
  onState?: (state: SseState) => void,
  onProtocolError?: (eventType: 'stage' | 'token') => void,
): () => void {
  const es = new EventSource(`/api/cases/${id}/events`)
  onState?.('connecting')
  es.onopen = () => onState?.('open')
  // EventSource 在连接失败时自动重连；CLOSED 表示已关闭（通常由 close() 触发）
  es.onerror = () => {
    if (es.readyState === EventSource.CLOSED) {
      onState?.('reconnecting')
    } else {
      onState?.('reconnecting')
    }
  }
  es.addEventListener('stage', (ev: MessageEvent<string>) => {
    try {
      const event = parseWorkflowEvent(JSON.parse(ev.data))
      onEvent(event)
      // EventSource 会在服务端正常关闭后自动重连；业务终态必须由客户端显式 close，
      // 否则终态工单会形成“连接→立即关闭→重连”的永久循环。
      if (['DONE', 'HOLD', 'FAILED'].includes(event.stage)) {
        es.close()
        onState?.('closed')
      }
    } catch {
      es.close()
      onState?.('closed')
      onProtocolError?.('stage')
    }
  })
  if (onToken) {
    es.addEventListener('token', (ev: MessageEvent<string>) => {
      try {
        onToken(parseSseToken(JSON.parse(ev.data)))
      } catch {
        es.close()
        onState?.('closed')
        onProtocolError?.('token')
      }
    })
  }
  return () => {
    es.close()
    onState?.('closed')
  }
}

/** 解析工单中的尽调报告 */
export function parseReport(caseItem: CaseItem): DueDiligenceReport | null {
  if (!caseItem.reportJson) return null
  try {
    return parseDueDiligenceReport(JSON.parse(caseItem.reportJson))
  } catch {
    return null
  }
}
