import { api } from './http'

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
  hiddenTestReady: boolean
  hiddenTestDatasetHash: string | null
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
  efficiency: {
    p95LatencyBudgetMs: number
    averageTokensPerCaseBudget: number
    observedP95LatencyMs: number
    observedAverageTokensPerCase: number | null
    latencyPass: boolean | null
    tokenPass: boolean | null
  } | null
  runtime: { provider: string; configuredModel: string; realModel: boolean; fallbackUsed: boolean } | null
}

/** 仅运行 DEV 分片；后端会拒绝 Mock/fallback，并保持 TEST 标准答案冻结。 */
export async function runAgentDevEval(): Promise<AgentEvalResult> {
  return (await api.post('/eval/agent/dev')).data
}

export async function getAgentEvalDatasetSummary(): Promise<AgentEvalDatasetSummary> {
  return (await api.get('/eval/agent/dataset')).data
}
