import axios from 'axios'

const TOKEN_KEY = 'aml_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

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
  executionVersion: number
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
  reviewerRiskLevel: string
  decision: string
  comment: string
  createdAt: string
}

export const api = axios.create({ baseURL: '/api', timeout: 120000 })

api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      clearToken()
      window.location.href = '/'
    }
    return Promise.reject(err)
  },
)

// ---------- 认证 ----------
export async function login(username: string, password: string): Promise<{ token: string; username: string; role: string }> {
  return (await api.post('/auth/login', { username, password })).data
}

// ---------- 工单 ----------
export async function listCases(page = 0, size = 20): Promise<Page<CaseItem>> {
  return (await api.get('/cases', { params: { page, size } })).data
}

export async function listCustomers(): Promise<Customer[]> {
  return (await api.get('/cases/customers')).data
}

export async function createCase(customerId: string, alertRule: string): Promise<CaseItem> {
  return (await api.post('/cases', { customerId, alertRule, autoProcess: true })).data
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

/** 订阅工单工作流实时进度（SSE 通过 HttpOnly Cookie 认证，JWT 不进入 URL），返回取消订阅函数 */
export function subscribeCase(
  id: number,
  onEvent: (e: WorkflowEvent) => void,
  onToken?: (token: string) => void,
): () => void {
  const es = new EventSource(`/api/cases/${id}/events`)
  es.addEventListener('stage', (ev: MessageEvent<string>) => {
    try {
      onEvent(JSON.parse(ev.data))
    } catch {
      /* ignore */
    }
  })
  if (onToken) {
    es.addEventListener('token', (ev: MessageEvent<string>) => {
      try {
        onToken(JSON.parse(ev.data).token)
      } catch {
        /* ignore */
      }
    })
  }
  return () => es.close()
}

/** 解析工单中的尽调报告 */
export function parseReport(caseItem: CaseItem): DueDiligenceReport | null {
  if (!caseItem.reportJson) return null
  try {
    return JSON.parse(caseItem.reportJson)
  } catch {
    return null
  }
}

// ---------- 人工复核 ----------
export async function listPendingReviews(): Promise<CaseItem[]> {
  return (await api.get('/reviews/pending')).data
}

export async function submitReview(
  caseId: number,
  body: { reviewerRiskLevel: string; decision: string; comment: string },
): Promise<ManualReview> {
  return (await api.post(`/reviews/${caseId}`, body)).data
}

export async function reviewStats(): Promise<{ reviewedCount: number; agreementRate: number; approvedCount: number; rejectedCount: number; escalatedCount: number }> {
  return (await api.get('/reviews/stats')).data
}

// ---------- 评测 ----------
export interface RuleRegressionReport {
  totalCases: number
  highRiskRecallRate: number
  lowRiskFalsePositiveRate: number
  accuracy: number
  manualReviewMissCount: number
  manualReviewTotal: number
  p50DurationMs: number
  p95DurationMs: number
  confusionMatrix: number[][]
  details: Array<{
    id: string
    scenario: string
    expectedRiskLevel: string
    baselineRiskLevel: string
    finalRiskLevel: string
    escalated: boolean
  }>
}

/** 确定性规则回归，不代表真实模型或 Agent 效果。 */
export async function runRuleRegression(): Promise<RuleRegressionReport> {
  return (await api.post('/eval/rules')).data
}

export interface AgentEvalDatasetSummary {
  datasetId: string
  version: string
  sourceType: string
  annotationMethod: string
  reviewStatus: string
  totalCases: number
  splitCounts: Record<string, number>
  scenarioCounts: Record<string, number>
  riskLevelCounts: Record<string, number>
}

export async function getAgentEvalStatus(): Promise<{
  ready: boolean
  datasetReady: boolean
  enabledSplit: 'DEV'
  dataset: AgentEvalDatasetSummary
  message: string
}> {
  return (await api.get('/eval/agent/status')).data
}

/** 仅运行 DEV 分片；后端会拒绝 Mock/fallback，并保持 TEST 标准答案冻结。 */
export async function runAgentDevEval(): Promise<Record<string, unknown>> {
  return (await api.post('/eval/agent/dev')).data
}

export async function getAgentEvalDatasetSummary(): Promise<AgentEvalDatasetSummary> {
  return (await api.get('/eval/agent/dataset')).data
}

export async function runRagEval(): Promise<Record<string, unknown>> {
  return (await api.post('/eval/rag')).data
}
