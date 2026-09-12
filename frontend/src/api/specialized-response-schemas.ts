import { array, boolean, number, object, record, string, type FieldRule } from './contract-runtime'

const nullableString = string({ nullable: true })
const nullableNumber = number({ nullable: true })
const nullableBoolean = boolean({ nullable: true })
const strings = array(string())
const nullableStrings = array(string(), { nullable: true })

const dueDiligenceReport = object({
  // 评测接口会原样保留模型的无效结构以生成 schemaViolations；因此每个模型字段都需显式允许 null，
  // 但仍验证非 null 值的精确类型，不能用任意 JSON 契约吞掉结构错误。
  customerId: nullableString,
  customerName: nullableString,
  riskLevel: nullableString,
  transactionProfile: nullableString,
  corporateProfile: nullableString,
  sanctions: nullableStrings,
  legalBasis: nullableStrings,
  riskPoints: nullableStrings,
  conclusion: nullableString,
  evidenceChain: nullableStrings,
  manualReviewRequired: nullableBoolean,
  findingCodes: nullableStrings,
  actionCodes: nullableStrings,
})

export const transactionWindowsSchema = object({
  asOfTime: string(),
  sourceSystem: string(),
  sourceVersion: string(),
  windows: array(
    object({
      days: number(),
      transactionCount: number(),
      currencyBreakdown: array(
        object({
          currency: string(),
          totalAmount: string(),
          incomingAmount: string(),
          outgoingAmount: string(),
          crossBorderAmount: string(),
        }),
      ),
      crossBorderCount: number(),
      nightCount: number(),
      topCounterparties: array(
        object({
          counterparty: string(),
          transactionCount: number(),
          amounts: array(object({ currency: string(), amount: string() })),
        }),
      ),
    }),
  ),
})

const sanctionCandidate = object({
  candidateFingerprint: string(),
  candidateName: string(),
  identityMasked: string(),
  listType: string(),
  detail: string(),
  severity: number(),
  score: number(),
  algorithmDecision: string({ enumValues: ['CONFIRMED', 'REVIEW_REQUIRED', 'DISMISSED'] }),
  decision: string({ enumValues: ['CONFIRMED', 'REVIEW_REQUIRED', 'DISMISSED'] }),
  reasonCodes: strings,
  explanation: string(),
  reviewDecision: string({ nullable: true, enumValues: ['CONFIRM', 'DISMISS', 'REQUEST_MORE_INFO'] }),
  reviewRevision: number(),
  reviewedBy: nullableString,
  reviewedAt: nullableString,
  reviewComment: nullableString,
})

export const sanctionScreeningSchema = object({
  customerId: string(),
  customerName: string(),
  status: string({ enumValues: ['CONFIRMED_MATCH', 'REVIEW_REQUIRED', 'NO_MATCH'] }),
  screenedAt: string(),
  sourceSystem: string(),
  sourceVersion: string(),
  candidates: array(sanctionCandidate),
})

export const verificationSchema = object({
  eventId: number(),
  artifactVersionId: number(),
  method: string(),
  observedFacts: string(),
  limitations: nullableString,
  result: string(),
  actor: string(),
  eventTime: string(),
})

export const issueReviewSchema = object({
  proposalId: number(),
  caseId: number(),
  issueId: number(),
  proposalRevision: number(),
  originalSeverity: string(),
  proposedSeverity: string(),
  reason: string(),
  evidenceReference: nullableString,
  proposedBy: string(),
  proposedAt: string(),
  status: string(),
  confirmedBy: nullableString,
  confirmedAt: nullableString,
  rejectedReason: nullableString,
})

export const refundReversalSchema = object({ reversalEventId: number() })

export const agentEvalDatasetSchema = object({
  datasetId: string(),
  version: string(),
  sourceType: string(),
  annotationMethod: string(),
  reviewStatus: string(),
  totalCases: number(),
  splitCounts: record(number()),
  scenarioCounts: record(number()),
  riskLevelCounts: record(number()),
  datasetHash: string(),
  hiddenTestReady: boolean(),
  hiddenTestDatasetHash: nullableString,
})

const rate = object({ numerator: number(), denominator: number(), value: nullableNumber }, { nullable: true })
const riskMetrics = object(
  {
    exactAccuracy: rate,
    highRiskRecall: rate,
    macroF1: nullableNumber,
    balancedAccuracy: nullableNumber,
    ordinalMae: nullableNumber,
    underClassificationCount: number(),
    criticalMissCount: number(),
    confusionMatrix: record(record(number())),
  },
  { nullable: true },
)
const binaryMetrics = object(
  { accuracy: rate, precision: rate, recall: rate, falseNegativeCount: number() },
  { nullable: true },
)
const toolCall = object({
  toolName: string(),
  arguments: record(string()),
  success: boolean(),
  argumentValid: boolean(),
  durationMs: number(),
  resultDigest: nullableString,
  error: nullableString,
})
const forbiddenCheck = object({ claimCode: string(), status: string(), reason: nullableString })
const modelSnapshot = object(
  {
    requestCount: number(),
    inputTokens: number(),
    outputTokens: number(),
    totalTokens: number(),
    modelName: nullableString,
    lastAssistantText: nullableString,
    error: nullableString,
    requestedTools: array(object({ toolName: string() })),
  },
  { nullable: true },
)
const evalCase = object({
  caseId: string(),
  scenario: string(),
  status: string(),
  invalidReason: nullableString,
  schemaViolations: strings,
  expectedRawRisk: nullableString,
  actualRawRisk: nullableString,
  rawRiskCorrect: boolean(),
  finalRisk: nullableString,
  finalRiskCorrect: boolean(),
  expectedEscalation: boolean(),
  actualRawEscalation: nullableBoolean,
  finalEscalation: boolean(),
  triggeredGuardrailRules: strings,
  requiredFindings: strings,
  missingFindings: strings,
  unsupportedFindings: strings,
  requiredActions: strings,
  missingActions: strings,
  unsupportedActions: strings,
  requiredEvidenceIds: strings,
  missingEvidenceIds: strings,
  expectedForbiddenClaims: strings,
  requiredTools: strings,
  toolCalls: array(toolCall),
  missingTools: strings,
  invalidArgumentCalls: number(),
  duplicateCalls: number(),
  forbiddenChecks: array(forbiddenCheck),
  endToEndTaskPass: boolean(),
  strictPass: boolean(),
  durationMs: number(),
  model: modelSnapshot,
  report: object(dueDiligenceReport.fields ?? {}, { nullable: true }),
})

export const agentEvalReportSchema = object({
  runId: string(),
  datasetId: string(),
  datasetVersion: string(),
  split: string(),
  promptVersion: string(),
  runtime: object(
    { provider: string(), configuredModel: string(), realModel: boolean(), fallbackUsed: boolean() },
    { nullable: true },
  ),
  runStatus: string(),
  invalidReason: nullableString,
  startedAt: string(),
  durationMs: number(),
  attempted: number(),
  completed: number(),
  scored: number(),
  invalid: number(),
  strictPassCount: number(),
  strictPassRate: rate,
  taskPassCount: number(),
  taskPassRate: rate,
  forbiddenClaimGatePolicy: string(),
  schema: object({ successRate: rate, violationCounts: record(number()) }, { nullable: true }),
  rawRisk: riskMetrics,
  finalRisk: riskMetrics,
  guardrails: object(
    {
      upgradeCount: number(),
      falseUpgradeCount: number(),
      preventedCriticalMissCount: number(),
      triggeredRuleCounts: record(number()),
    },
    { nullable: true },
  ),
  rawEscalation: binaryMetrics,
  finalEscalation: binaryMetrics,
  findings: object(
    { microRecall: rate, microPrecision: rate, fullCoverageRate: rate, unsupportedCodeCount: number() },
    { nullable: true },
  ),
  actions: object(
    { microRecall: rate, microPrecision: rate, fullCoverageRate: rate, unsupportedCodeCount: number() },
    { nullable: true },
  ),
  citations: object({ evidenceIdRecall: rate, fullCoverageRate: rate }, { nullable: true }),
  tools: object(
    {
      requiredToolRecall: rate,
      callPrecision: rate,
      argumentAccuracy: rate,
      exactCoverageRate: rate,
      invalidArgumentCalls: number(),
      duplicateCalls: number(),
      executionFailures: number(),
      averageCallsPerCase: number(),
    },
    { nullable: true },
  ),
  forbiddenClaims: object(
    { complianceRate: rate, detectorCoverage: rate, violationCount: number(), unscorableCount: number() },
    { nullable: true },
  ),
  latency: object({ p50Ms: number(), p95Ms: number() }, { nullable: true }),
  tokens: object(
    { inputTokens: number(), outputTokens: number(), totalTokens: number(), modelRequests: number() },
    { nullable: true },
  ),
  efficiency: object(
    {
      p95LatencyBudgetMs: number(),
      averageTokensPerCaseBudget: number(),
      observedP95LatencyMs: number(),
      observedAverageTokensPerCase: nullableNumber,
      latencyPass: nullableBoolean,
      tokenPass: nullableBoolean,
    },
    { nullable: true },
  ),
  cases: array(evalCase),
})

const assistantTerminalSchemas = {
  completed: object({ runId: string(), resultType: string() }),
  refused: object({ runId: string(), resultType: string(), message: string() }),
  failed: object({ runId: string(), errorCode: string() }),
} as const

export type AssistantTerminalType = keyof typeof assistantTerminalSchemas

export function assistantTerminalSchema(type: AssistantTerminalType): FieldRule {
  return assistantTerminalSchemas[type]
}
