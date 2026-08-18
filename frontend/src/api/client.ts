import axios from 'axios'

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

// CSRF 防护：从可读的 XSRF-TOKEN Cookie 取值，写入 X-XSRF-TOKEN header（写请求）
function readCsrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? match[1] : ''
}

// 认证走 HttpOnly Cookie（浏览器自动携带），不再手动附加 Authorization 头
api.interceptors.request.use((config) => {
  const method = (config.method || 'GET').toUpperCase()
  if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
    config.headers['X-XSRF-TOKEN'] = readCsrfToken()
  }
  return config
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    // /auth/me 返回 401 表示"未登录"这一正常状态（初始加载校验），不应触发整页跳转，
    // 否则会与 App 挂载时的 checkAuth 形成 401 → 跳转 → 再校验 的无限循环。
    if (err.response?.status === 401 && !err.config?.url?.includes('/auth/me')) {
      // 通过自定义事件通知 App 清理登录态并回到登录界面（避免整页跳转丢失当前工作上下文）
      window.dispatchEvent(new CustomEvent('auth:expired'))
    }
    return Promise.reject(err)
  },
)

// ---------- 认证 ----------
export type UserRole = 'ADMIN' | 'REVIEWER' | 'ANALYST'

export interface AuthenticatedUser {
  username: string
  role: UserRole
}

/** 强制生成 CSRF Token Cookie（登录/恢复登录态后调用，供写请求携带 X-XSRF-TOKEN） */
export async function initCsrf(): Promise<void> {
  await api.get('/auth/csrf')
}

export async function login(username: string, password: string): Promise<AuthenticatedUser> {
  const user = (await api.post('/auth/login', { username, password })).data
  await initCsrf()
  return user
}

/** 刷新后恢复登录态（未认证由后端返回 401） */
export async function checkAuth(): Promise<AuthenticatedUser> {
  const user = (await api.get('/auth/me')).data
  await initCsrf()
  return user
}

export async function logout(): Promise<void> {
  await api.post('/auth/logout')
}

// ---------- 工单 ----------
export async function listCases(page = 0, size = 20): Promise<Page<CaseItem>> {
  return (await api.get('/cases', { params: { page, size } })).data
}

export interface CaseStats {
  total: number
  pending: number
  running: number
  hold: number
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

/** 工具调用轨迹（脱敏，不含参数明文；按执行版本倒序返回） */
export async function listToolTraces(id: number): Promise<ToolTrace[]> {
  return (await api.get(`/cases/${id}/tools`)).data
}

/** SSE 连接状态（用于界面展示"连接中/已连接/连接断开正在重连"） */
export type SseState = 'connecting' | 'open' | 'reconnecting'

/**
 * 订阅工单工作流实时进度（SSE 通过 HttpOnly Cookie 认证，JWT 不进入 URL），返回取消订阅函数。
 * @param onEvent  收到 stage 事件
 * @param onToken  收到 token 事件（可选，流式摘要）
 * @param onState  连接状态回调（可选，断线时 EventSource 自动重连，前端据此提示并拉取对账）
 */
export function subscribeCase(
  id: number,
  onEvent: (e: WorkflowEvent) => void,
  onToken?: (token: string) => void,
  onState?: (state: SseState) => void,
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
  return () => {
    onState?.('connecting')
    es.close()
  }
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
  body: { reviewerRiskLevel: string; decision: string; comment: string; expectedReviewRevision: number },
): Promise<ManualReview> {
  return (await api.post(`/reviews/${caseId}`, body)).data
}

export async function reviewStats(): Promise<{ reviewedCount: number; agreementRate: number; approvedCount: number; rejectedCount: number; escalatedCount: number }> {
  return (await api.get('/reviews/stats')).data
}

// ---------- 评测 ----------
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
  datasetHash: string
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

/** 评测率：显式分子/分母；分母为 0 时 value 为 null（不伪造指标） */
export interface EvalRate {
  numerator: number
  denominator: number
  value: number | null
}

/** 真实模型 Agent 评测结果（对应后端 AgentEvalReport 聚合字段） */
export interface AgentEvalResult {
  runId: string
  datasetId: string
  datasetVersion: string
  split: string
  promptVersion: string
  runStatus: string
  attempted: number
  completed: number
  scored: number
  strictPassCount: number
  strictPassRate: EvalRate | null
  taskPassRate: EvalRate | null
  rawRisk: { exactAccuracy: EvalRate | null; highRiskRecall: EvalRate | null } | null
  finalRisk: { exactAccuracy: EvalRate | null; highRiskRecall: EvalRate | null } | null
  tools: { requiredToolRecall: EvalRate | null } | null
  citations: { evidenceIdRecall: EvalRate | null } | null
  latency: { p50Ms: number; p95Ms: number } | null
  tokens: { inputTokens: number; outputTokens: number; totalTokens: number } | null
  runtime: { provider: string; configuredModel: string; realModel: boolean; fallbackUsed: boolean } | null
}

/** 仅运行 DEV 分片；后端会拒绝 Mock/fallback，并保持 TEST 标准答案冻结。 */
export async function runAgentDevEval(): Promise<AgentEvalResult> {
  return (await api.post('/eval/agent/dev')).data
}

export async function getAgentEvalDatasetSummary(): Promise<AgentEvalDatasetSummary> {
  return (await api.get('/eval/agent/dataset')).data
}

/** 将后端 ISO 时间格式化为"年-月-日 时:分:秒"；空值返回占位符 */
export function fmtDateTime(s: string | null | undefined): string {
  if (!s) return '-'
  // ISO 形如 "2026-08-18T09:03:57.532" → "2026-08-18 09:03:57"
  return s.replace('T', ' ').slice(0, 19)
}
