<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  addInvestigationEvidence,
  fmtDateTime,
  getCaseDossier,
  getCase,
  getCaseInvestigation,
  getCaseOperations,
  getSuspiciousTransactionReport,
  getTransactionWindows,
  listCaseReviews,
  listEnhancedDueDiligence,
  listLogs,
  listToolTraces,
  parseReport,
  processCase,
  retryCase,
  returnSuspiciousReport,
  splitAlertToNewCase,
  submitEnhancedDueDiligence,
  updateAlertCoverage,
  updateInvestigationHypothesis,
  reviewSanctionCandidate,
  screenSanctions,
  subscribeCase,
  type CaseItem,
  type AmlAlert,
  type CaseInvestigation,
  type CaseOperationsView,
  type DueDiligenceReport,
  type EnhancedDueDiligenceRequest,
  type EnhancedDueDiligenceEvidenceSubmission,
  type EvidenceStance,
  type InvestigationEvidenceType,
  type InvestigationHypothesis,
  type ManualReview,
  type SanctionScreeningResult,
  type SanctionCandidateMatch,
  type SuspiciousTransactionReport,
  type TransactionWindowView,
  type ToolTrace,
  type WorkflowEvent,
} from '../api/client'
import { riskMeta, statusMeta, TERMINAL_STATUSES } from '../constants/case'
import { coverageGap, coverageGapText, expectedConclusionFor, isInvestigationRevisionConflict, type CoverageGap } from '../utils/investigation'
import { Back, Download, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import { currentUser } from '../auth'
import ExplanationWorkspace from './ExplanationWorkspace.vue'

const props = defineProps<{ caseId: number }>()
const emit = defineEmits<{ (e: 'back'): void; (e: 'open-case', id: number): void }>()

const caseItem = ref<CaseItem | null>(null)
const report = ref<DueDiligenceReport | null>(null)
const loadError = ref(false)
const detailLoading = ref(true)
const logs = ref<{ stage: string; content: string; at: string }[]>([])
const toolTraces = ref<ToolTrace[]>([])
const reviewHistory = ref<ManualReview[]>([])
const eddRequests = ref<EnhancedDueDiligenceRequest[]>([])
const eddHistoryLoadError = ref(false)
const suspiciousReport = ref<SuspiciousTransactionReport | null>(null)
const reportWorkflowLoadError = ref(false)
const returningReport = ref(false)
const investigation = ref<CaseInvestigation | null>(null)
const transactionWindows = ref<TransactionWindowView | null>(null)
const caseOperations = ref<CaseOperationsView | null>(null)
const investigationLoadError = ref(false)
const investigationActionId = ref<number | null>(null)
const evidenceDialogOpen = ref(false)
const evidenceHypothesis = ref<InvestigationHypothesis | null>(null)
const evidenceType = ref<InvestigationEvidenceType>('TRANSACTION')
const evidenceStance = ref<EvidenceStance>('SUPPORTS')
const evidenceReference = ref('')
const evidenceSummary = ref('')
const evidenceSubmitting = ref(false)
const sanctionScreening = ref<SanctionScreeningResult | null>(null)
const screeningLoading = ref(false)
const dossierLoading = ref(false)
const reviewingFingerprint = ref('')
const eddDialogOpen = ref(false)
const respondingEdd = ref<EnhancedDueDiligenceRequest | null>(null)
const eddResponseSummary = ref('')
const eddEvidenceItems = ref<EnhancedDueDiligenceEvidenceSubmission[]>([])
const eddSubmitting = ref(false)
const doneKeys = ref<Set<string>>(new Set())
const currentKey = ref('')
const logOpen = ref<string[]>(['log'])
const streamingText = ref('')
const sseState = ref<'connecting' | 'open' | 'reconnecting' | 'closed'>('connecting')

let unsubscribe: (() => void) | null = null
let refreshTimer: number | null = null
/** 连接序号：并发 connect() 时只有最新一次生效，防止旧订阅写回已重置的页面状态 */
let connectSeq = 0
/** 历史 + 实时日志去重（后端 SSE 事件与 case_log 落库内容一致） */
let seenKeys = new Set<string>()

const workflowStages = [
  { key: 'PLANNING', label: '任务规划', desc: '解析预警工单，拆解子任务' },
  { key: 'COLLECTING', label: '数据采集', desc: '并行调用多源数据工具' },
  { key: 'REASONING', label: '风险推理', desc: '综合研判风险特征' },
  { key: 'GUARDRAIL', label: '规则护栏', desc: '制裁名单 / 评级一致性校验' },
  { key: 'REPORTING', label: '报告生成', desc: '结构化尽调初审报告' },
  { key: 'DONE', label: '完成', desc: '归档 / 转人工' },
]

function clearRefreshTimer() {
  if (refreshTimer !== null) {
    window.clearTimeout(refreshTimer)
    refreshTimer = null
  }
}

/** 合并实时与历史日志：按 stage|content 去重、限制条数上限，避免重复与无限增长 */
function pushLog(stage: string, content: string, at: string) {
  const key = `${stage}|${content}`
  if (seenKeys.has(key)) return
  seenKeys.add(key)
  logs.value.push({ stage, content, at })
  if (logs.value.length > 300) logs.value.shift()
  currentKey.value = stage
  doneKeys.value.add(stage)
  // REPORTING 到达时 REASONING 已完成（后端保证各阶段按序发事件，此处仅兜底标记）
  if (stage === 'REPORTING') doneKeys.value.add('REASONING')
  if (stage === 'DONE') {
    workflowStages.forEach((s) => doneKeys.value.add(s.key))
    doneKeys.value.add('REASONING')
  }
}

function handleEvent(ev: WorkflowEvent) {
  pushLog(ev.stage, ev.content, new Date().toLocaleTimeString())
  // 终态事件后延迟拉取一次详情，对齐报告/状态字段（定时器统一在重连/卸载时清理）
  if ((TERMINAL_STATUSES as readonly string[]).includes(ev.stage)) {
    scheduleRefresh()
  }
}

function scheduleRefresh() {
  clearRefreshTimer()
  refreshTimer = window.setTimeout(refresh, 800)
}

async function refresh() {
  try {
    const c = await getCase(props.caseId)
    caseItem.value = c
    report.value = parseReport(c)
    loadError.value = false
  } catch {
    // 不静默吞错：标记错误，让页面展示"加载失败"态而非空白
    loadError.value = true
  }
  // SSE 终态到达后同步刷新调查就绪状态与运营待办，避免只刷新风险报告
  if (caseItem.value && ['HOLD', 'DONE', 'REPORT_PENDING', 'FAILED'].includes(caseItem.value.status)) {
    try {
      const [investigationView, windows, operations] = await Promise.all([
        getCaseInvestigation(props.caseId), getTransactionWindows(props.caseId), getCaseOperations(props.caseId),
      ])
      investigation.value = investigationView
      transactionWindows.value = windows
      caseOperations.value = operations
      investigationLoadError.value = false
    } catch {
      investigationLoadError.value = true
    }
  }
}

async function connect() {
  const seq = ++connectSeq
  unsubscribe?.()
  clearRefreshTimer()
  logs.value = []
  seenKeys = new Set()
  doneKeys.value = new Set()
  currentKey.value = ''
  streamingText.value = ''
  toolTraces.value = []
  reviewHistory.value = []
  eddRequests.value = []
  eddHistoryLoadError.value = false
  suspiciousReport.value = null
  reportWorkflowLoadError.value = false
  investigation.value = null
  transactionWindows.value = null
  caseOperations.value = null
  investigationLoadError.value = false
  sanctionScreening.value = null
  sseState.value = 'connecting'
  detailLoading.value = true

  // 先订阅实时事件，再拉历史：避免"拉取完成才订阅"的空窗丢事件；
  // 历史与订阅窗口内的实时事件按 stage|content 去重合并。
  unsubscribe = subscribeCase(
    props.caseId,
    handleEvent,
    (token) => {
      streamingText.value += token
    },
    (state) => {
      sseState.value = state
    },
  )

  try {
    const c = await getCase(props.caseId)
    if (seq !== connectSeq) return
    caseItem.value = c
    report.value = parseReport(c)
    loadError.value = false
  } catch {
    if (seq !== connectSeq) return
    loadError.value = true
  } finally {
    if (seq === connectSeq) detailLoading.value = false
  }
  if (seq !== connectSeq) return

  try {
    const history = await listLogs(props.caseId)
    if (seq !== connectSeq) return
    history.forEach((l) => pushLog(l.stage, l.content, ''))
  } catch {
    // 历史日志拉取失败不影响订阅实时进度
    if (seq !== connectSeq) return
    if (!logs.value.length) {
      logs.value.push({ stage: 'PLANNING', content: '历史日志加载失败，正在订阅实时进度…', at: '' })
    }
  }

  try {
    const traces = await listToolTraces(props.caseId)
    if (seq !== connectSeq) return
    toolTraces.value = traces
  } catch {
    // 工具轨迹加载失败不影响主流程（日志/报告仍可查看）
    if (seq !== connectSeq) return
    toolTraces.value = []
  }

  try {
    const reviews = await listCaseReviews(props.caseId)
    if (seq !== connectSeq) return
    reviewHistory.value = reviews
  } catch {
    if (seq !== connectSeq) return
    reviewHistory.value = []
  }

  try {
    const requests = await listEnhancedDueDiligence(props.caseId)
    if (seq !== connectSeq) return
    eddRequests.value = requests
    eddHistoryLoadError.value = false
  } catch {
    if (seq !== connectSeq) return
    eddRequests.value = []
    eddHistoryLoadError.value = true
  }

  const [investigationResult, windowsResult, operationsResult] = await Promise.allSettled([
      getCaseInvestigation(props.caseId), getTransactionWindows(props.caseId), getCaseOperations(props.caseId),
  ])
  if (seq !== connectSeq) return
  investigation.value = investigationResult.status === 'fulfilled' ? investigationResult.value : null
  transactionWindows.value = windowsResult.status === 'fulfilled' ? windowsResult.value : null
  caseOperations.value = operationsResult.status === 'fulfilled' ? operationsResult.value : null
  investigationLoadError.value = investigationResult.status === 'rejected' || windowsResult.status === 'rejected'

  if (caseItem.value?.reviewDisposition === 'CONFIRM_SUSPICIOUS' && canReviewSanctions.value) {
    try {
      const reportItem = await getSuspiciousTransactionReport(props.caseId)
      if (seq !== connectSeq) return
      suspiciousReport.value = reportItem
      reportWorkflowLoadError.value = false
    } catch {
      if (seq !== connectSeq) return
      suspiciousReport.value = null
      reportWorkflowLoadError.value = true
    }
  }

  if (caseItem.value) {
    screeningLoading.value = true
    try {
      const screening = await screenSanctions(caseItem.value.customerId)
      if (seq !== connectSeq) return
      sanctionScreening.value = screening
    } catch {
      if (seq !== connectSeq) return
      sanctionScreening.value = null
    } finally {
      if (seq === connectSeq) screeningLoading.value = false
    }
  }
}

// 同一组件实例在 /cases/1 → /cases/2 间复用（浏览器前进/后退）时重建订阅，避免展示旧工单数据
watch(() => props.caseId, () => connect())

onMounted(connect)

onUnmounted(() => {
  clearRefreshTimer()
  unsubscribe?.()
})

async function retryLoad() {
  loadError.value = false
  caseItem.value = null
  await connect()
}

async function handleRetry() {
  try {
    await retryCase(props.caseId)
    ElMessage.success('已重新入队，正在执行')
    await connect()
  } catch {
    ElMessage.error('人工重试失败，可能该工单不可重试，请刷新后重试')
  }
}

async function handleProcess() {
  const alertCount = investigation.value?.alerts.length ?? 0
  try {
    await ElMessageBox.confirm(
      `当前关联预警 ${alertCount} 条。开始调查后案件将进入执行，不能再归并或拆分预警。`,
      '开始调查',
      { confirmButtonText: '开始调查', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  try {
    await processCase(props.caseId)
    ElMessage.success('已请求开始调查（重复请求会由后端幂等去重）')
    await connect()
  } catch {
    ElMessage.error('开始调查失败，可能该案件非待处理状态（已入队或已开始），请刷新后重试')
  }
}

async function handleDossierDownload() {
  dossierLoading.value = true
  try {
    const dossier = await getCaseDossier(props.caseId)
    const blob = new Blob([JSON.stringify(dossier, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `aml-case-${props.caseId}-dossier.json`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success(`调查档案已导出，完整性摘要 ${dossier.contentHash.slice(0, 12)}…`)
  } catch {
    ElMessage.error('调查档案导出失败，请稍后重试')
  } finally {
    dossierLoading.value = false
  }
}

function screeningTagType(decision: string): 'danger' | 'warning' | 'info' {
  if (decision === 'CONFIRMED') return 'danger'
  if (decision === 'REVIEW_REQUIRED') return 'warning'
  return 'info'
}

function screeningDecisionText(decision: string): string {
  if (decision === 'CONFIRMED') return '确定命中'
  if (decision === 'REVIEW_REQUIRED') return '待人工核验'
  return '已排除'
}

const canRetry = computed(() => caseItem.value && caseItem.value.status === 'FAILED')
const canProcess = computed(() => caseItem.value && caseItem.value.status === 'PENDING')
const canReviewSanctions = computed(() => ['REVIEWER', 'ADMIN'].includes(currentUser.value?.role ?? ''))
const canSubmitEdd = computed(() => ['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? ''))
// 调查录入仅限待处理 / 人工处理中案件；DONE / REPORT_PENDING 只展示调查结果，不提供会被后端拒绝的编辑入口
const canEditInvestigation = computed(() =>
  ['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '')
  && !!caseItem.value
  && ['PENDING', 'HOLD'].includes(caseItem.value.status))
/** 拆分入口与调查编辑解耦：容量超限等执行前失败的 FAILED 案件也允许拆分后受控恢复。 */
const canSplitOnCase = computed(() =>
  ['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '')
  && !!caseItem.value
  && ['PENDING', 'FAILED'].includes(caseItem.value.status))
/** 调查契约 v1 案件的人工处理语义：调查未就绪 → 分析员待补齐；就绪 → 复核员决定。 */
const holdPhaseHint = computed(() => {
  if (!caseItem.value || caseItem.value.status !== 'HOLD'
      || (investigation.value && investigation.value.contractVersion < 1)) return null
  if (!investigation.value) return null
  return investigation.value.readyForFinalReview
    ? '自动分析完成，调查已就绪，等待复核员形成最终处置（确认可疑 / 排除预警）。'
    : '自动分析完成，待补齐调查（假设、证据与逐预警覆盖）后进入人工复核。'
})

async function handleCandidateReview(
  candidate: SanctionCandidateMatch,
  decision: 'CONFIRM' | 'DISMISS' | 'REQUEST_MORE_INFO',
) {
  const actionLabel = decision === 'CONFIRM' ? '确认命中' : decision === 'DISMISS' ? '排除候选' : '要求补充材料'
  try {
    const { value } = await ElMessageBox.prompt(
      `将“${candidate.candidateName}”标记为：${actionLabel}`,
      '制裁候选人工核验',
      {
        confirmButtonText: '提交核验',
        cancelButtonText: '取消',
        inputPlaceholder: '请填写判断依据（必填）',
        inputValidator: (text: string) => {
          if (!text?.trim()) return '人工核验必须填写判断依据'
          if (text && text.length > 500) return '核验意见最多 500 字'
          return true
        },
      },
    )
    reviewingFingerprint.value = candidate.candidateFingerprint
    sanctionScreening.value = await reviewSanctionCandidate(caseItem.value!.customerId, {
      candidateFingerprint: candidate.candidateFingerprint,
      decision,
      comment: value?.trim() ?? '',
      expectedRevision: candidate.reviewRevision,
    })
    ElMessage.success('候选核验决定已保存')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    if (error?.response?.status === 409) {
      ElMessage.warning('候选已被其他复核人更新，正在刷新最新结果')
      sanctionScreening.value = await screenSanctions(caseItem.value!.customerId)
    } else {
      ElMessage.error('候选核验提交失败，请稍后重试')
    }
  } finally {
    reviewingFingerprint.value = ''
  }
}

function isDone(key: string): boolean {
  return doneKeys.value.has(key)
}

function isActive(key: string): boolean {
  return currentKey.value === key
}

function legalTitle(text: string): string {
  return text.split('\n')[0].replace(/^【|】$/g, '')
}

function legalBody(text: string): string {
  const idx = text.indexOf('】')
  return idx >= 0 ? text.slice(idx + 1) : text
}

function snapPrefix(id: string | null): string {
  return id && id.length > 12 ? id.slice(0, 12) + '…' : (id ?? '-')
}

const reviewDispositionText: Record<string, string> = {
  CONFIRM_SUSPICIOUS: '确认可疑',
  EXCLUDE_FALSE_POSITIVE: '排除预警',
  REQUEST_ENHANCED_DUE_DILIGENCE: '补充尽调',
}

const legacyReviewDecisionText: Record<string, string> = {
  APPROVE: '历史决定：批准',
  REJECT: '历史决定：驳回',
  ESCALATE: '历史决定：升级',
}

function reviewDecisionLabel(decision: string): string {
  return reviewDispositionText[decision] ?? legacyReviewDecisionText[decision] ?? decision
}

const eddRequiredItemText: Record<string, string> = {
  CUSTOMER_IDENTITY: '客户身份及有效证件',
  BENEFICIAL_OWNER: '受益所有人及控制关系',
  SOURCE_OF_FUNDS: '资金来源证明',
  TRANSACTION_PURPOSE: '交易目的说明',
  COUNTERPARTY_RELATIONSHIP: '交易对手关系说明',
  SUPPORTING_CONTRACT_INVOICE: '合同、发票等业务凭证',
  WATCHLIST_IDENTITY: '名单身份核验材料',
}

const eddStatusText: Record<string, string> = {
  OPEN: '待补充',
  SUBMITTED: '已提交待复核',
  RESOLVED: '已完成',
  CANCELLED: '已撤销',
}

const reportStatusText: Record<string, string> = {
  PENDING_SUBMISSION: '待报送',
  SUBMITTED: '已报送',
  RETURNED_FOR_CORRECTION: '退回补正',
}

const eddSourceSystemOptions = [
  { value: 'KYC_PLATFORM', label: 'KYC 平台' },
  { value: 'CORE_BANKING', label: '核心银行系统' },
  { value: 'DOCUMENT_MANAGEMENT', label: '文档管理系统' },
  { value: 'SANCTIONS_SCREENING', label: '名单筛查系统' },
  { value: 'CUSTOMER_PROVIDED', label: '客户提供' },
]

function formatEddRequiredItems(row: unknown): string {
  const request = row as EnhancedDueDiligenceRequest
  return request.requiredItems.map((item: string) => eddRequiredItemText[item] ?? item).join('、')
}

function openEddResponse(row: unknown) {
  const request = row as EnhancedDueDiligenceRequest
  respondingEdd.value = request
  eddResponseSummary.value = ''
  eddEvidenceItems.value = request.requiredItems.map((requiredItemCode) => ({
    requiredItemCode,
    sourceSystem: 'DOCUMENT_MANAGEMENT',
    sourceReference: '',
    contentSha256: '',
  }))
  eddDialogOpen.value = true
}

async function submitEddResponse() {
  if (!respondingEdd.value) return
  if (eddResponseSummary.value.trim().length < 10) {
    ElMessage.warning('材料说明至少 10 个字符')
    return
  }
  const incomplete = eddEvidenceItems.value.some((item) =>
    !item.sourceSystem || !item.sourceReference.trim() || !/^[a-fA-F0-9]{64}$/.test(item.contentSha256.trim()))
  if (incomplete) {
    ElMessage.warning('请为每项材料填写来源记录编号和 64 位 SHA-256 摘要')
    return
  }
  eddSubmitting.value = true
  try {
    await submitEnhancedDueDiligence(props.caseId, respondingEdd.value.id, {
      expectedRevision: respondingEdd.value.revision,
      responseSummary: eddResponseSummary.value.trim(),
      evidenceItems: eddEvidenceItems.value.map((item) => ({
        ...item,
        sourceReference: item.sourceReference.trim(),
        contentSha256: item.contentSha256.trim().toLowerCase(),
      })),
    })
    ElMessage.success('补充尽调材料已提交，等待复核')
    eddDialogOpen.value = false
    eddRequests.value = await listEnhancedDueDiligence(props.caseId)
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '补充尽调材料提交失败，请刷新后重试')
  } finally {
    eddSubmitting.value = false
  }
}

async function returnReportForCorrection() {
  if (!suspiciousReport.value || suspiciousReport.value.status !== 'SUBMITTED') return
  try {
    const { value } = await ElMessageBox.prompt(
      '退回后案件会重新进入待报送状态，请填写外部系统退回或内部复核发现的问题。',
      '退回报告补正',
      {
        inputType: 'textarea',
        inputPlaceholder: '填写需补正的具体内容（至少 10 个字符）',
        inputValidator: (text: string) => text?.trim().length >= 10 || '退回原因至少 10 个字符',
      },
    )
    returningReport.value = true
    suspiciousReport.value = await returnSuspiciousReport(
      props.caseId, suspiciousReport.value.revision, value.trim(),
    )
    await refresh()
    ElMessage.success('报告已退回补正，案件重新进入待报送')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message ?? '退回补正失败，请刷新后重试')
  } finally {
    returningReport.value = false
  }
}

const investigationScenarioText: Record<string, string> = {
  STRUCTURING: '拆分交易规避监测',
  RAPID_MOVEMENT: '资金快进快出',
  CROSS_BORDER_ANOMALY: '异常跨境交易',
  PROFILE_MISMATCH: '交易与客户画像不匹配',
  COMPLEX_OWNERSHIP: '复杂受益所有权',
  SANCTIONS_WATCHLIST: '名单身份核验',
}

const evidenceTypeText: Record<InvestigationEvidenceType, string> = {
  TRANSACTION: '交易记录',
  CUSTOMER_PROFILE: '客户画像',
  BENEFICIAL_OWNERSHIP: '受益所有权',
  SANCTIONS_SCREENING: '名单筛查',
  DOCUMENT: '业务凭证',
  EXTERNAL_DATA: '外部数据',
  LEGAL: '法规依据',
}

const evidenceStanceText: Record<EvidenceStance, string> = {
  SUPPORTS: '支持假设',
  CONTRADICTS: '反向证据',
}

const hypothesisStatusText = { OPEN: '待研判', CONFIRMED: '已确认', REJECTED: '已排除' }
const coverageConclusionText = { PENDING: '待覆盖', SUSPICIOUS: '支持可疑', EXPLAINED: '已有合理解释' }
const operationsPriorityText = { CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', NORMAL: '常规' }
const operationsPhaseText = {
  INVESTIGATION: '案件调查', REVIEW: '人工复核', ENHANCED_DUE_DILIGENCE: '补充尽调',
  REPORTING: '可疑报告报送', COMPLETED: '已完成',
}

function coverageFor(alertId: number) {
  return investigation.value?.coverage.find((item) => item.alertId === alertId)
}

/** 覆盖与当前假设的差距：决定“形成结论”还是“重新确认”入口（详见 utils/investigation）。 */
function coverageGapFor(alertId: number): CoverageGap | null {
  const coverage = coverageFor(alertId)
  return coverage ? coverageGap(coverage, hypothesisFor(coverage.hypothesisId)) : null
}

function hypothesisFor(id: number | null | undefined) {
  return investigation.value?.hypotheses.find((item) => item.id === id)
}

async function reloadInvestigation() {
  const [investigationView, windows] = await Promise.all([
    getCaseInvestigation(props.caseId), getTransactionWindows(props.caseId),
  ])
  investigation.value = investigationView
  transactionWindows.value = windows
  investigationLoadError.value = false
  // 重新加载成功后事实恢复新鲜；stale 标记只在“写入成功但刷新失败”时置位
  investigationStale.value = false
}

/**
 * A4-01：写入成功但刷新失败后的事实恢复入口。
 * 只重新加载，不重放任何写入；成功后解除 stale 限制，失败保持限制并提示。
 */
async function reloadInvestigationFacts() {
  try {
    await reloadInvestigation()
  } catch {
    ElMessage.error('重新加载调查事实失败，请检查网络后重试；在新事实加载前不能继续编辑')
  }
}

function openInvestigationEvidence(hypothesis: InvestigationHypothesis) {
  evidenceHypothesis.value = hypothesis
  evidenceType.value = hypothesis.requiredEvidenceTypes[0] ?? 'TRANSACTION'
  evidenceStance.value = 'SUPPORTS'
  evidenceReference.value = caseItem.value?.snapshotId
    ? `SNAPSHOT-${caseItem.value.snapshotId}` : `CASE-${props.caseId}-EVIDENCE`
  evidenceSummary.value = ''
  evidenceDialogOpen.value = true
}

async function submitInvestigationEvidence() {
  if (!evidenceHypothesis.value) return
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}$/.test(evidenceReference.value.trim())
      || evidenceSummary.value.trim().length < 10) {
    ElMessage.warning('请填写有效证据引用和至少 10 个字符的调查摘要')
    return
  }
  evidenceSubmitting.value = true
  try {
    await addInvestigationEvidence(props.caseId, evidenceHypothesis.value.id, {
      evidenceType: evidenceType.value,
      evidenceReference: evidenceReference.value.trim(),
      stance: evidenceStance.value,
      findingSummary: evidenceSummary.value.trim(),
    })
    await reloadInvestigation()
    evidenceDialogOpen.value = false
    ElMessage.success('调查证据已关联到假设')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '证据关联失败，请刷新后重试')
  } finally {
    evidenceSubmitting.value = false
  }
}

/**
 * W1/A3-02：按假设隔离的判断草稿（页面内存，不持久化）。
 * 记录目标结论与依据；保存成功或用户明确放弃时清理；任何失败（冲突刷新失败、网络错误）
 * 均保留，重新打开时恢复，禁止在失败分支误提示“已保存”。
 */
const hypothesisDrafts = ref<Record<number, { conclusion: 'CONFIRMED' | 'REJECTED'; rationale: string }>>({})

/** A4-01：写入已成功但调查事实刷新失败时置位——旧事实不能继续当作当前事实编辑或重复提交。 */
const investigationStale = ref(false)

/**
 * 确认/排除/修改/重申调查假设：与覆盖编辑同一冲突恢复原则。
 * 冲突时保留用户输入，刷新最新假设状态与依据，由用户明确确认后以新版本提交；
 * 成功提示区分幂等重申（版本不变、覆盖未失效）与实际改判。
 */
async function decideInvestigationHypothesis(
  hypothesis: InvestigationHypothesis, status: 'CONFIRMED' | 'REJECTED',
) {
  if (investigationStale.value) {
    // A4-01：写入已成功但事实未刷新，旧版本/旧依据不能作为当前事实再次提交
    ElMessage.warning('上次保存成功后未加载到最新调查事实；请先点击“重新加载调查事实”核对新依据后再继续编辑')
    return
  }
  investigationActionId.value = hypothesis.id
  const draft = hypothesisDrafts.value[hypothesis.id]
  const restoringDraft = !!draft && draft.conclusion === status
  let current = hypothesis
  let expectedRevision = hypothesis.revision
  let analysis = restoringDraft ? draft!.rationale : ''
  let conflictRound = false
  try {
    for (;;) {
      const redecide = current.status !== 'OPEN'
      const reaffirm = redecide && status === current.status
      const title = reaffirm ? '重申调查假设判断' : (redecide ? '修改调查假设判断' : (status === 'CONFIRMED' ? '确认调查假设' : '排除调查假设'))
      const restored = restoringDraft
        ? `已恢复你上次未保存的输入（目标结论：${status === 'CONFIRMED' ? '确认' : '排除'}）。\n\n`
        : ''
      const message = conflictRound
        ? `调查假设已被他人更新（当前状态：${hypothesisStatusName(current.status)}；当前版本 ${current.revision}）。\n`
          + `最新判断依据：${current.rationale ?? '（未填写）'}。\n`
          + `你已输入的判断依据已保留，请核对后再次确认。`
        : restored + (reaffirm
          ? '重申当前判断：保持原结论，仅重新确认依据；若依据与当前完全相同，后端幂等返回且不会递增版本。'
          : redecide
            ? '修改判断将递增假设版本，并使引用该假设的预警覆盖结论失效（需重新确认）。请填写新的判断依据。'
            : status === 'CONFIRMED'
              ? '说明支持证据如何证明该风险假设。'
              : '说明哪些反向证据足以排除该风险假设。')
      let value: string
      try {
        const prompt = await ElMessageBox.prompt(message, title, {
          inputType: 'textarea',
          inputValue: analysis || (redecide && !restoringDraft ? (current.rationale ?? '') : ''),
          inputValidator: (text: string) => text?.trim().length >= 10 || '判断依据至少 10 个字符',
        })
        value = prompt.value.trim()
      } catch (dialogError) {
        if (dialogError === 'cancel' || dialogError === 'close') {
          // 用户明确放弃编辑：清理草稿
          delete hypothesisDrafts.value[hypothesis.id]
        }
        return
      }
      analysis = value
      // 发请求前先把草稿写到对话框外的页面级状态（按案件与假设隔离）
      hypothesisDrafts.value[current.id] = { conclusion: status, rationale: analysis }
      try {
        const result = await updateInvestigationHypothesis(props.caseId, current.id, {
          expectedRevision,
          status,
          rationale: analysis,
        })
        // 写入明确成功：清理草稿并给出真实的保存结果；刷新失败不再当作提交失败。
        delete hypothesisDrafts.value[current.id]
        if (result.revision === expectedRevision) {
          ElMessage.success('判断依据未变化（幂等确认）：未递增版本，覆盖结论未失效')
        } else if (redecide && reaffirm) {
          ElMessage.success('判断依据已更新并递增版本；引用该假设的覆盖结论已重置为待确认')
        } else {
          ElMessage.success(status === 'CONFIRMED' ? '调查假设已确认' : '调查假设已排除')
        }
        try {
          await reloadInvestigation()
        } catch {
          // A4-01：已保存，但事实加载失败 —— 不声称更新失败、不承诺未保存草稿，限制后续编辑直至重新加载
          investigationStale.value = true
          ElMessage.warning('已保存，但最新调查事实加载失败；请点击“重新加载调查事实”核对新依据后再继续编辑，不要基于旧依据重复提交')
          return
        }
        return
      } catch (submitError: any) {
        if (!isInvestigationRevisionConflict(submitError)) {
          // 普通失败：草稿保留，可重新打开恢复；不误提示已保存
          ElMessage.error(submitError?.response?.data?.message ?? '假设更新失败，请检查必需证据是否完整（你输入的判断依据已保留）')
          return
        }
        conflictRound = true
        try {
          await reloadInvestigation()
        } catch {
          ElMessage.error('刷新调查事实失败，请稍后重新执行该操作；你输入的判断依据已保留')
          return
        }
        const refreshed = investigation.value?.hypotheses.find((item) => item.id === current.id)
        if (!refreshed) {
          ElMessage.warning('该调查假设已不存在（可能随预警拆分移动），本次提交已取消（你输入的判断依据已保留）')
          return
        }
        if (refreshed.status === 'OPEN') {
          ElMessage.warning('该调查假设已被重置为未决，请重新执行确认或排除操作（你输入的判断依据已保留）')
          return
        }
        current = refreshed
        expectedRevision = refreshed.revision
        continue
      }
    }
  } finally {
    investigationActionId.value = null
  }
}

/** W1：按预警隔离的覆盖分析草稿（页面内存，不持久化）；仅在保存成功或用户明确放弃时清理。 */
const coverageDrafts = ref<Record<number, string>>({})

/** 最近一次冲突错误（用于区分“假设被改判”与“覆盖被他人更新”的提示）。 */
let lastConflictError: unknown = null

interface CoverageEditContext {
  coverage: { revision: number; analysisSummary: string | null }
  hypothesis: { id: number; revision: number; status: string; rationale: string | null }
  conclusion: 'SUSPICIOUS' | 'EXPLAINED'
  gap: CoverageGap | null
}

/** 提交前重新解析当前事实；返回 blocked 时说明原因，不提交无效 ID 或错误结论。 */
function resolveCoverageEditContext(alertId: number): { context: CoverageEditContext } | { blocked: string } {
  if (investigationStale.value) {
    return { blocked: '上次保存成功后未加载到最新调查事实；请先点击“重新加载调查事实”后再继续编辑' }
  }
  if (!canEditInvestigation.value) {
    return { blocked: '案件当前为只读状态，不能提交覆盖结论' }
  }
  const coverage = coverageFor(alertId)
  if (!coverage) {
    return { blocked: '该预警的覆盖结论不存在（可能已被拆分到其它案件），请刷新后重试' }
  }
  const hypothesis = hypothesisFor(coverage.hypothesisId)
  if (!hypothesis) {
    return { blocked: '该预警关联的调查假设不存在，请刷新后重试' }
  }
  if (hypothesis.status === 'OPEN') {
    return { blocked: '该预警关联的调查假设仍是未决状态，请先完成确认或排除' }
  }
  const conclusion = expectedConclusionFor(hypothesis)
  if (!conclusion) {
    return { blocked: '关联调查假设状态无法推导覆盖结论，请刷新后重试' }
  }
  return { context: { coverage, hypothesis, conclusion, gap: coverageGapFor(alertId) } }
}

function conflictTypeText(error: unknown): string {
  const type = (error as { response?: { data?: { conflict?: { type?: string } } } })
    ?.response?.data?.conflict?.type
  return type === 'COVERAGE' ? '该预警的覆盖结论已被他人更新' : '判断依据的假设已被他人改判'
}

function coverageConclusionLabel(conclusion: 'SUSPICIOUS' | 'EXPLAINED'): string {
  return conclusion === 'SUSPICIOUS' ? '确认可疑' : '合理解释'
}

/**
 * 统一的覆盖结论编辑入口（形成结论 / 重新确认共用同一状态机）。
 * <ul>
 *   <li>草稿按预警隔离存于页面内存；发请求前先写入，仅保存成功或用户明确放弃时清理；</li>
 *   <li>版本冲突（409 + INVESTIGATION_REVISION_CONFLICT 协议）时刷新调查事实、保留草稿，
 *       展示最新假设依据与将提交的结论，用户明确确认后才以新版本提交；</li>
 *   <li>不把任意 409/412 当作冲突自动刷新重试；刷新失败/二次冲突均保留草稿并给出重试提示。</li>
 * </ul>
 */
async function editAlertCoverage(alertId: number, mode: 'CONCLUDE' | 'RECONFIRM') {
  const opened = resolveCoverageEditContext(alertId)
  if ('blocked' in opened) {
    ElMessage.warning(opened.blocked)
    return
  }
  let context = opened.context
  let analysis = mode === 'RECONFIRM'
    ? (coverageDrafts.value[alertId] ?? context.coverage.analysisSummary ?? '')
    : (coverageDrafts.value[alertId] ?? '')
  let conflictRound = false
  try {
    for (;;) {
      const isFirstRound = !conflictRound
      const title = isFirstRound
        ? (mode === 'RECONFIRM'
          ? '重新确认覆盖结论'
          : (context.conclusion === 'SUSPICIOUS' ? '形成可疑覆盖结论' : '形成合理解释结论'))
        : '重新确认覆盖结论（已刷新）'
      const message = isFirstRound
        ? (mode === 'RECONFIRM'
          ? `${coverageGapText(context.gap ?? 'PENDING')}。\n\n当前假设依据：${context.hypothesis.rationale ?? '（未填写）'}`
          : '逐项说明该调查假设和证据如何覆盖本条预警，不可只填写结论。')
        : `${conflictTypeText(lastConflictError)}。\n\n最新假设状态：${hypothesisStatusName(context.hypothesis.status)}；`
          + `最新判断依据：${context.hypothesis.rationale ?? '（未填写）'}。\n`
          + `确认后将提交的覆盖结论：${coverageConclusionLabel(context.conclusion)}（以当前假设状态为准）。\n`
          + `你已输入的分析已保留，请核对后再次确认。`
      let dialogValue: string
      try {
        const { value } = await ElMessageBox.prompt(message, title, {
          inputType: 'textarea',
          inputValue: analysis,
          inputValidator: (text: string) => text?.trim().length >= 10 || '覆盖分析至少 10 个字符',
        })
        dialogValue = value.trim()
      } catch (dialogError) {
        if (dialogError === 'cancel' || dialogError === 'close') {
          // 用户明确放弃编辑：清理草稿
          delete coverageDrafts.value[alertId]
        }
        return
      }
      analysis = dialogValue
      // 发请求前先把草稿写到对话框外的响应式状态
      coverageDrafts.value[alertId] = analysis
      investigationActionId.value = alertId
      try {
        await updateAlertCoverage(props.caseId, alertId, {
          expectedRevision: context.coverage.revision,
          hypothesisId: context.hypothesis.id,
          expectedHypothesisRevision: context.hypothesis.revision,
          conclusion: context.conclusion,
          analysisSummary: analysis,
        })
        // 写入明确成功：清理草稿并给出真实的保存结果；刷新失败不再当作提交失败。
        delete coverageDrafts.value[alertId]
        ElMessage.success(isFirstRound && mode === 'RECONFIRM'
          ? '覆盖结论已重新确认（版本绑定已补齐）'
          : (isFirstRound ? '预警覆盖结论已保存' : '覆盖结论已基于最新依据保存'))
        try {
          await reloadInvestigation()
        } catch {
          // A4-01：已保存，但事实加载失败 —— 不声称更新失败、不承诺未保存草稿，限制后续编辑直至重新加载
          investigationStale.value = true
          ElMessage.warning('已保存，但最新调查事实加载失败；请点击“重新加载调查事实”核对新结论后再继续编辑，不要基于旧事实重复提交')
          return
        }
        return
      } catch (submitError: any) {
        if (isInvestigationRevisionConflict(submitError)) {
          // 版本冲突：保留草稿，刷新后由用户明确确认再提交（不自动重放）
          lastConflictError = submitError
          conflictRound = true
          try {
            await reloadInvestigation()
          } catch {
            ElMessage.error('刷新调查事实失败；已保留你输入的分析，请稍后重新打开该预警的确认入口')
            return
          }
          const refreshed = resolveCoverageEditContext(alertId)
          if ('blocked' in refreshed) {
            ElMessage.warning(`无法继续提交：${refreshed.blocked}（已保留你输入的分析）`)
            return
          }
          context = refreshed.context
          continue
        }
        ElMessage.error(submitError?.response?.data?.message ?? '覆盖结论保存失败，请刷新后重试（已保留你输入的分析）')
        return
      }
    }
  } finally {
    investigationActionId.value = null
  }
}

function hypothesisStatusName(status: string): string {
  return status === 'CONFIRMED' ? '已确认' : (status === 'REJECTED' ? '已排除' : '未决')
}

/**
 * 重新确认存量/过期覆盖（A1）：与“形成结论”共用同一编辑状态机。
 * 不要求反转调查判断；结论以关联假设当前状态为准。
 */
async function reconfirmAlertCoverage(alertId: number) {
  await editAlertCoverage(alertId, 'RECONFIRM')
}

async function splitCaseAlert(alert: { id: number; revision: number }) {
  try {
    const { value } = await ElMessageBox.prompt(
      '拆分后该预警会形成独立案件并重新调查，请说明其与当前案件不应合并的原因。',
      '拆分预警',
      {
        inputType: 'textarea',
        inputValidator: (text: string) => text?.trim().length >= 10 || '拆分原因至少 10 个字符',
      },
    )
    investigationActionId.value = alert.id
    const created = await splitAlertToNewCase(alert.id, alert.revision, value.trim(), { autoProcess: false })
    if (caseItem.value?.status === 'FAILED') {
      ElMessage.success(`预警已拆分为案件 #${created.id}；原案件可在确认剩余预警后执行人工重试继续调查`)
      await connect()
    } else {
      ElMessage.success(`预警已拆分为案件 #${created.id}（暂未开始调查，可继续调整边界后开始调查）`)
      emit('open-case', created.id)
    }
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message ?? '预警拆分失败，请刷新后重试')
  } finally {
    investigationActionId.value = null
  }
}

function formatAmount(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value ?? 0)
}

const reviewReasonText: Record<string, string> = {
  TRANSACTION_PATTERN_INCONSISTENT: '交易模式与客户画像不一致',
  SANCTIONS_OR_WATCHLIST_MATCH: '制裁或关注名单命中',
  SOURCE_OF_FUNDS_UNCLEAR: '资金来源或用途不清',
  CUSTOMER_DUE_DILIGENCE_CONCERN: '客户尽调信息存在疑点',
  VERIFIED_LEGITIMATE_PURPOSE: '已核实合理交易目的',
  CUSTOMER_PROFILE_CONSISTENT: '交易与客户画像一致',
  DUPLICATE_OR_KNOWN_ACTIVITY: '重复预警或已知正常活动',
  WATCHLIST_FALSE_POSITIVE: '名单同名或身份误匹配',
  MISSING_CUSTOMER_INFORMATION: '客户资料缺失',
  SOURCE_OF_FUNDS_EVIDENCE_REQUIRED: '需补充资金来源证明',
  BENEFICIAL_OWNER_VERIFICATION_REQUIRED: '需核实受益所有人',
  WATCHLIST_IDENTITY_VERIFICATION_REQUIRED: '需补充名单身份核验材料',
}
</script>

<template>
  <div v-if="detailLoading" class="detail-loading">
    <el-icon class="is-loading load-icon"><RefreshRight /></el-icon>
    <span>正在加载工单…</span>
  </div>
  <div v-else-if="loadError" class="detail-error">
    <div class="err-mark">!</div>
    <p class="err-msg">工单加载失败，可能是工单不存在或网络异常。</p>
    <el-button type="primary" @click="retryLoad">重新加载</el-button>
  </div>
  <div v-else-if="caseItem" class="detail">
    <div class="detail-bar">
      <el-button size="small" @click="emit('back')">
        <el-icon><Back /></el-icon>
        <span>返回列表</span>
      </el-button>
      <div class="bar-right">
        <el-button size="small" :loading="dossierLoading" @click="handleDossierDownload">
          <el-icon><Download /></el-icon>
          <span>导出调查档案</span>
        </el-button>
        <el-button v-if="canProcess" size="small" type="primary" @click="handleProcess">
          <el-icon><VideoPlay /></el-icon>
          <span>开始调查</span>
        </el-button>
        <el-button v-if="canRetry" size="small" type="warning" @click="handleRetry">
          <el-icon><RefreshRight /></el-icon>
          <span>人工重试</span>
        </el-button>
        <el-tag
          :type="statusMeta[caseItem.status]?.type ?? 'info'"
          size="large"
          effect="dark"
          round
        >
          {{ statusMeta[caseItem.status]?.text ?? caseItem.status }}
        </el-tag>
        <span v-if="report?.riskLevel" class="rk" :class="riskMeta[report.riskLevel]?.cls">
          {{ report.riskLevel }}
        </span>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title screening-title">
        可解释制裁筛查
        <el-tag v-if="sanctionScreening" size="small"
          :type="sanctionScreening.status === 'CONFIRMED_MATCH' ? 'danger' : sanctionScreening.status === 'REVIEW_REQUIRED' ? 'warning' : 'success'">
          {{ sanctionScreening.status === 'CONFIRMED_MATCH' ? '存在确定命中' : sanctionScreening.status === 'REVIEW_REQUIRED' ? '需要人工核验' : '未发现命中' }}
        </el-tag>
      </h3>
      <div v-if="screeningLoading" class="log-empty">正在核验名单候选…</div>
      <div v-else-if="!sanctionScreening" class="log-empty">筛查服务暂不可用，不影响已归档的尽调报告。</div>
      <template v-else>
        <div class="screening-meta">
          数据源 {{ sanctionScreening.sourceSystem }} / {{ sanctionScreening.sourceVersion }} ·
          {{ fmtDateTime(sanctionScreening.screenedAt) }}
        </div>
        <div v-if="sanctionScreening.candidates.length === 0" class="screening-empty">未召回同名或同证件号候选</div>
        <div v-for="candidate in sanctionScreening.candidates" :key="`${candidate.listType}-${candidate.candidateName}`" class="candidate-row">
          <div class="candidate-score" :class="`score-${candidate.decision.toLowerCase()}`">{{ candidate.score }}</div>
          <div class="candidate-main">
            <div class="candidate-head">
              <strong>{{ candidate.candidateName }}</strong>
              <el-tag size="small" :type="screeningTagType(candidate.decision)" effect="plain">
                {{ screeningDecisionText(candidate.decision) }}
              </el-tag>
              <span class="candidate-list">{{ candidate.listType }} · 级别 {{ candidate.severity }}</span>
            </div>
            <div class="candidate-explain">{{ candidate.explanation }}</div>
            <div v-if="candidate.reviewDecision" class="candidate-review">
              <strong>人工核验：</strong>
              {{ candidate.reviewDecision === 'CONFIRM' ? '确认命中' : candidate.reviewDecision === 'DISMISS' ? '已排除' : '等待补充材料' }}
              · v{{ candidate.reviewRevision }} · {{ candidate.reviewedBy }} · {{ fmtDateTime(candidate.reviewedAt) }}
              <span v-if="candidate.reviewComment">（{{ candidate.reviewComment }}）</span>
            </div>
            <div class="candidate-foot">
              <span>身份标识 {{ candidate.identityMasked }}</span>
              <span v-for="code in candidate.reasonCodes" :key="code" class="code-chip info">{{ code }}</span>
            </div>
            <div v-if="canReviewSanctions" class="candidate-actions">
              <el-button size="small" type="danger" plain
                :loading="reviewingFingerprint === candidate.candidateFingerprint"
                @click="handleCandidateReview(candidate, 'CONFIRM')">确认命中</el-button>
              <el-button size="small" type="success" plain
                :disabled="!!reviewingFingerprint"
                @click="handleCandidateReview(candidate, 'DISMISS')">排除候选</el-button>
              <el-button size="small" type="warning" plain
                :disabled="!!reviewingFingerprint"
                @click="handleCandidateReview(candidate, 'REQUEST_MORE_INFO')">补充材料</el-button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div class="card">
      <h3 class="card-title">工单 #{{ caseItem.id }} · {{ caseItem.customerName }}（{{ caseItem.customerId }}）</h3>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="预警规则">{{ caseItem.alertRule }}</el-descriptions-item>
        <el-descriptions-item label="模型原始评级">
          <span v-if="caseItem.rawRiskLevel" class="rk" :class="riskMeta[caseItem.rawRiskLevel]?.cls">{{ caseItem.rawRiskLevel }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="最终评级">
          <span v-if="caseItem.riskLevel" class="rk" :class="riskMeta[caseItem.riskLevel]?.cls">{{ caseItem.riskLevel }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="执行版本"><span class="mono-num">v{{ caseItem.executionVersion }}</span></el-descriptions-item>
        <el-descriptions-item label="复核版本"><span class="mono-num">v{{ caseItem.reviewRevision }}</span></el-descriptions-item>
        <el-descriptions-item label="人工处置">{{ caseItem.reviewDisposition ? (reviewDispositionText[caseItem.reviewDisposition] ?? caseItem.reviewDisposition) : '待处置' }}</el-descriptions-item>
        <el-descriptions-item label="处置原因">{{ caseItem.reviewReasonCode ? (reviewReasonText[caseItem.reviewReasonCode] ?? caseItem.reviewReasonCode) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="处置时间">{{ fmtDateTime(caseItem.reviewedAt) }}</el-descriptions-item>
        <el-descriptions-item label="快照 ID"><span class="mono-num snap">{{ snapPrefix(caseItem.snapshotId) }}</span></el-descriptions-item>
        <el-descriptions-item v-if="caseItem.failureMessage" label="失败原因" :span="3">
          {{ caseItem.failureMessage }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">
          <span class="mono-num time">{{ fmtDateTime(caseItem.createdAt) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="模型">
          <span class="mono-num">{{ caseItem.modelName || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源">
          <span v-if="caseItem.reportSource" class="src">{{ caseItem.reportSource === 'AGENT' ? 'Agent 生成' : '规则降级' }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-descriptions v-if="caseOperations" :column="3" border size="small" class="operations-detail">
        <el-descriptions-item label="运营优先级">
          <el-tag :type="caseOperations.priority === 'CRITICAL' ? 'danger' : caseOperations.priority === 'HIGH' ? 'warning' : 'info'" effect="dark">
            {{ operationsPriorityText[caseOperations.priority] }} · {{ caseOperations.priorityScore }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前责任阶段">{{ operationsPhaseText[caseOperations.phase] }}</el-descriptions-item>
        <el-descriptions-item label="责任队列">{{ caseOperations.assignedTo || caseOperations.assignedUnit }}</el-descriptions-item>
        <el-descriptions-item label="阶段截止时间">
          <span :class="{ overdue: caseOperations.overdue }">{{ caseOperations.dueAt ? fmtDateTime(caseOperations.dueAt) : '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="SLA 状态">
          <strong :class="caseOperations.overdue ? 'overdue' : 'ops-ok'">
            {{ caseOperations.overdue ? `已逾期 ${Math.abs(caseOperations.minutesRemaining)} 分钟` : `剩余 ${caseOperations.minutesRemaining} 分钟` }}
          </strong>
        </el-descriptions-item>
        <el-descriptions-item label="优先原因">{{ caseOperations.priorityReasons.join('；') }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="card">
      <h3 class="card-title investigation-title">
        客户整体交易窗口
        <span v-if="transactionWindows" class="window-asof">
          截至 {{ fmtDateTime(transactionWindows.asOfTime) }} · {{ transactionWindows.sourceSystem }} / {{ transactionWindows.sourceVersion }}
        </span>
      </h3>
      <el-alert v-if="investigationLoadError" type="error" :closable="false" show-icon
        title="调查链路或交易窗口加载失败；在数据恢复前请勿作出最终处置。" />
      <div v-else-if="transactionWindows" class="window-grid">
        <div v-for="window in transactionWindows.windows" :key="window.days" class="window-card">
          <strong>近 {{ window.days }} 天</strong>
          <div class="window-metrics">
            <span>交易 <b>{{ window.transactionCount }}</b> 笔</span>
            <span>跨境 {{ window.crossBorderCount }} 笔</span>
            <span>夜间 {{ window.nightCount }} 笔</span>
          </div>
          <div class="currency-list">
            <span v-if="!window.currencyBreakdown.length" class="muted">暂无金额</span>
            <span v-for="currency in window.currencyBreakdown" :key="currency.currency">
              <b>{{ currency.currency }} {{ formatAmount(currency.totalAmount) }}</b>
              · 入 {{ formatAmount(currency.incomingAmount) }}
              · 出 {{ formatAmount(currency.outgoingAmount) }}
              <template v-if="currency.crossBorderAmount"> · 跨境 {{ formatAmount(currency.crossBorderAmount) }}</template>
            </span>
          </div>
          <div class="counterparty-list">
            <span class="muted">主要交易对手</span>
            <span v-if="!window.topCounterparties.length" class="muted">暂无</span>
            <span v-for="party in window.topCounterparties" :key="party.counterparty">
              {{ party.counterparty }} · {{ party.transactionCount }} 笔 ·
              {{ party.amounts.map((item) => `${item.currency} ${formatAmount(item.amount)}`).join(' / ') }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="investigation" class="card">
      <h3 class="card-title investigation-title">
        假设—证据—结论调查链
        <el-tag :type="investigation.contractVersion >= 1 ? 'primary' : 'info'" size="small" effect="plain">
          {{ investigation.contractVersion >= 1 ? `调查契约 v${investigation.contractVersion}` : '存量兼容案件' }}
        </el-tag>
      </h3>
      <p v-if="investigation.contractVersion < 1" class="hint">
        该案件创建于调查契约启用前，可查看迁移预警，但最终处置暂不强制执行新门槛。
      </p>
      <!-- A4-01：写入已成功但事实刷新失败——旧事实不能当作当前事实，提供显式重新加载入口 -->
      <el-alert v-if="investigationStale" type="warning" :closable="false" show-icon
        title="已保存的写入尚未反映到当前页面（调查事实加载失败）；在重新加载前不能继续编辑">
        <template #default>
          <el-button size="small" type="primary" plain @click="reloadInvestigationFacts">重新加载调查事实</el-button>
        </template>
      </el-alert>
      <el-alert v-if="holdPhaseHint" type="info" :closable="false" show-icon :title="holdPhaseHint" />
      <el-alert v-if="investigation.contractVersion >= 1 && !investigation.readyForFinalReview
        && investigation.generalBlockers.length" type="warning" :closable="false" show-icon
        title="调查尚未完成，最终处置被阻断">
        <template #default>{{ investigation.generalBlockers.join('；') }}</template>
      </el-alert>

      <h4 class="investigation-subtitle">关联预警（{{ investigation.alerts.length }}）</h4>
      <el-table :data="investigation.alerts" stripe>
        <el-table-column prop="externalAlertId" label="预警编号" width="180" />
        <el-table-column label="场景" width="170">
          <template #default="{ row }">{{ investigationScenarioText[row.scenarioCode] ?? row.scenarioCode }}</template>
        </el-table-column>
        <el-table-column prop="ruleCode" label="规则" width="145" />
        <el-table-column prop="hitReason" label="命中原因" min-width="220" show-overflow-tooltip />
        <el-table-column label="覆盖结论" width="130">
          <template #default="{ row }">
            <el-tag :type="coverageFor(row.id)?.conclusion === 'SUSPICIOUS' ? 'danger' : coverageFor(row.id)?.conclusion === 'EXPLAINED' ? 'success' : 'warning'" effect="plain">
              {{ coverageConclusionText[coverageFor(row.id)?.conclusion ?? 'PENDING'] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canEditInvestigation || canSplitOnCase" label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canEditInvestigation && coverageGapFor(row.id) === 'PENDING'" size="small"
              :loading="investigationActionId === row.id" @click="editAlertCoverage(row.id, 'CONCLUDE')">形成结论</el-button>
            <el-button v-else-if="canEditInvestigation && coverageGapFor(row.id)" size="small" type="warning" plain
              :loading="investigationActionId === row.id" @click="reconfirmAlertCoverage(row.id)">重新确认</el-button>
            <el-tooltip v-if="canEditInvestigation && coverageGapFor(row.id) && coverageGapFor(row.id) !== 'PENDING'"
              :content="coverageGapText(coverageGapFor(row.id)!)" placement="top">
              <span class="muted">需重新确认</span>
            </el-tooltip>
            <!-- 拆分入口：待处理可调整边界；容量超限等执行前失败（无任何调查产出）的 FAILED 案件也可受控拆分后重试 -->
            <el-button v-if="canSplitOnCase && investigation.alerts.length > 1"
              size="small" type="warning" plain
              :loading="investigationActionId === row.id" @click="splitCaseAlert(row as AmlAlert)">拆分</el-button>
          </template>
        </el-table-column>
      </el-table>

      <h4 class="investigation-subtitle">调查假设（{{ investigation.hypotheses.length }}）</h4>
      <div class="hypothesis-list">
        <div v-for="hypothesis in investigation.hypotheses" :key="hypothesis.id" class="hypothesis-card">
          <div class="hypothesis-head">
            <div>
              <strong>{{ hypothesis.title }}</strong>
              <span class="hypothesis-code">{{ hypothesis.hypothesisCode }}</span>
            </div>
            <el-tag :type="hypothesis.status === 'CONFIRMED' ? 'danger' : hypothesis.status === 'REJECTED' ? 'success' : 'warning'" effect="plain">
              {{ hypothesisStatusText[hypothesis.status] }}
            </el-tag>
          </div>
          <p class="investigation-question">{{ hypothesis.investigationQuestion }}</p>
          <div class="required-evidence">
            <span class="muted">必需证据</span>
            <span v-for="type in hypothesis.requiredEvidenceTypes" :key="type" class="code-chip info">{{ evidenceTypeText[type] }}</span>
          </div>
          <div v-if="hypothesis.evidence.length" class="evidence-chain-list">
            <div v-for="item in hypothesis.evidence" :key="item.id" class="evidence-chain-row">
              <el-tag size="small" :type="item.stance === 'SUPPORTS' ? 'danger' : 'success'" effect="plain">
                {{ evidenceStanceText[item.stance] }}
              </el-tag>
              <strong>{{ evidenceTypeText[item.evidenceType] }}</strong>
              <span class="mono-num">{{ item.evidenceReference }}</span>
              <span>{{ item.findingSummary }}</span>
            </div>
          </div>
          <p v-else class="muted">尚未关联证据。</p>
          <p v-if="hypothesis.rationale" class="hypothesis-rationale"><strong>判断依据：</strong>{{ hypothesis.rationale }}</p>
          <div v-if="canEditInvestigation && hypothesis.status === 'OPEN'" class="hypothesis-actions">
            <el-button size="small" @click="openInvestigationEvidence(hypothesis)">关联证据</el-button>
            <el-button size="small" type="danger" plain :loading="investigationActionId === hypothesis.id"
              @click="decideInvestigationHypothesis(hypothesis, 'CONFIRMED')">确认假设</el-button>
            <el-button size="small" type="success" plain :loading="investigationActionId === hypothesis.id"
              @click="decideInvestigationHypothesis(hypothesis, 'REJECTED')">排除假设</el-button>
          </div>
          <div v-else-if="canEditInvestigation" class="hypothesis-actions">
            <el-button size="small" @click="openInvestigationEvidence(hypothesis)">补充证据</el-button>
            <!-- 重申判断：保持原结论，仅重新确认依据；与原依据完全相同时后端幂等返回不递增版本 -->
            <el-button size="small"
              :type="hypothesis.status === 'CONFIRMED' ? 'danger' : 'success'" plain
              :loading="investigationActionId === hypothesis.id"
              @click="decideInvestigationHypothesis(hypothesis, hypothesis.status as 'CONFIRMED' | 'REJECTED')">重申判断</el-button>
            <el-button size="small"
              :type="hypothesis.status === 'CONFIRMED' ? 'success' : 'danger'" plain
              :loading="investigationActionId === hypothesis.id"
              @click="decideInvestigationHypothesis(hypothesis,
                hypothesis.status === 'CONFIRMED' ? 'REJECTED' : 'CONFIRMED')">修改判断</el-button>
            <span class="muted">修改判断将使引用该假设的覆盖结论失效，需要重新确认</span>
          </div>
        </div>
      </div>

      <div v-if="investigation.contractVersion >= 1" class="review-gates">
        <el-alert :type="investigation.confirmSuspiciousBlockers.length ? 'warning' : 'success'" :closable="false" show-icon
          :title="investigation.confirmSuspiciousBlockers.length ? '确认可疑仍被阻断' : '已满足确认可疑调查门槛'">
          <template v-if="investigation.confirmSuspiciousBlockers.length" #default>
            {{ investigation.confirmSuspiciousBlockers.join('；') }}
          </template>
        </el-alert>
        <el-alert :type="investigation.excludeFalsePositiveBlockers.length ? 'warning' : 'success'" :closable="false" show-icon
          :title="investigation.excludeFalsePositiveBlockers.length ? '排除预警仍被阻断' : '已满足排除预警调查门槛'">
          <template v-if="investigation.excludeFalsePositiveBlockers.length" #default>
            {{ investigation.excludeFalsePositiveBlockers.join('；') }}
          </template>
        </el-alert>
      </div>
    </div>

    <ExplanationWorkspace v-if="investigation && investigation.contractVersion >= 2"
      :case-id="props.caseId" />

    <div v-if="reviewHistory.length" class="card">
      <h3 class="card-title">人工处置记录</h3>
      <el-table :data="reviewHistory" stripe>
        <el-table-column label="时间" width="168">
          <template #default="{ row }"><span class="mono-num time">{{ fmtDateTime(row.completedAt || row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="reviewerId" label="复核人" width="110" />
        <el-table-column label="结论" width="130">
          <template #default="{ row }">{{ reviewDecisionLabel(row.decision) }}</template>
        </el-table-column>
        <el-table-column label="原因" min-width="190">
          <template #default="{ row }">{{ row.reasonCode ? (reviewReasonText[row.reasonCode] ?? row.reasonCode) : '-' }}</template>
        </el-table-column>
        <el-table-column prop="reviewerRiskLevel" label="复核评级" width="100" />
        <el-table-column prop="comment" label="分析记录" min-width="240" show-overflow-tooltip />
      </el-table>
    </div>

    <div v-if="eddRequests.length || eddHistoryLoadError" class="card">
      <h3 class="card-title">补充尽调任务</h3>
      <el-alert v-if="eddHistoryLoadError" type="error" :closable="false" title="补充尽调历史加载失败，当前内容可能不完整，请刷新重试" />
      <el-table v-else :data="eddRequests" stripe>
        <el-table-column label="轮次" width="72">
          <template #default="{ row }"><span class="mono-num">#{{ row.roundNo }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="126">
          <template #default="{ row }">
            <el-tag :type="row.status === 'OPEN' ? 'warning' : row.status === 'SUBMITTED' ? 'success' : 'info'" effect="plain">
              {{ eddStatusText[row.status] ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="需补材料" min-width="220">
          <template #default="{ row }">{{ formatEddRequiredItems(row) }}</template>
        </el-table-column>
        <el-table-column label="承办" width="160">
          <template #default="{ row }">{{ row.assignedTo || '-' }}<br /><span class="muted">{{ row.assignedUnit || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="截止时间" width="168">
          <template #default="{ row }">
            <span class="mono-num time" :class="{ overdue: row.overdue }">
              {{ fmtDateTime(row.dueAt) }}{{ row.overdue ? '（已逾期）' : '' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="提交结果" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.responseSummary ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="证据编号" min-width="160">
          <template #default="{ row }">
            <el-tooltip v-if="row.evidenceItems.length" placement="top" effect="light">
              <template #content>
                <div v-for="item in row.evidenceItems" :key="item.id" class="evidence-meta">
                  {{ item.evidenceId }} · {{ item.sourceSystem }} · {{ item.sourceReference }} · SHA-256 {{ item.contentSha256 }}
                </div>
              </template>
              <span class="mono-num evidence-link">{{ row.evidenceReferences.join('、') }}</span>
            </el-tooltip>
            <span v-else class="mono-num">-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canSubmitEdd" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="openEddResponse(row)">提交材料</el-button>
            <span v-else class="muted">{{ row.status === 'CANCELLED' ? row.cancellationReason : '已提交' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="suspiciousReport || reportWorkflowLoadError" class="card">
      <h3 class="card-title">可疑交易报告闭环</h3>
      <el-alert v-if="reportWorkflowLoadError" type="error" :closable="false" title="可疑交易报告状态加载失败，请刷新重试" />
      <el-descriptions v-else-if="suspiciousReport" :column="2" border>
        <el-descriptions-item label="报告状态">{{ reportStatusText[suspiciousReport.status] ?? suspiciousReport.status }}</el-descriptions-item>
        <el-descriptions-item label="外部受理编号"><span class="mono-num">{{ suspiciousReport.externalReference || '-' }}</span></el-descriptions-item>
        <el-descriptions-item label="报告理由" :span="2">{{ suspiciousReport.reportReason }}</el-descriptions-item>
        <el-descriptions-item v-if="suspiciousReport.returnReason" label="退回原因" :span="2">{{ suspiciousReport.returnReason }}</el-descriptions-item>
        <el-descriptions-item label="报送人">{{ suspiciousReport.submittedBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="报送时间">{{ fmtDateTime(suspiciousReport.submittedAt) }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="suspiciousReport?.status === 'SUBMITTED' && canReviewSanctions" class="report-actions">
        <el-button type="warning" plain :loading="returningReport" @click="returnReportForCorrection">退回补正</el-button>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">
        Agent 工作流
        <span class="sse-indicator" :class="sseState">
          <i class="sse-dot"></i>
          <span>{{ sseState === 'open' ? '实时连接' : sseState === 'reconnecting' ? '连接中断，正在重连…' : sseState === 'closed' ? '流程已结束' : '正在连接…' }}</span>
        </span>
      </h3>
      <div class="flow">
        <div
          v-for="(s, i) in workflowStages"
          :key="s.key"
          class="flow-node"
          :class="{ done: isDone(s.key), active: isActive(s.key) }"
        >
          <div class="node-circle">
            <span v-if="isDone(s.key)">✓</span>
            <span v-else>{{ i + 1 }}</span>
          </div>
          <div class="node-label">{{ s.label }}</div>
          <div class="node-desc">{{ s.desc }}</div>
          <div v-if="i < workflowStages.length - 1" class="node-link" :class="{ done: isDone(s.key) }"></div>
        </div>
      </div>
      <el-collapse v-model="logOpen" class="log-list">
        <el-collapse-item title="阶段日志（实时）" name="log">
          <div v-if="logs.length === 0" class="log-empty">暂无日志</div>
          <div v-for="(l, i) in logs" :key="i" class="log-line">
            <el-tag size="small" effect="plain">{{ l.stage }}</el-tag>
            <span class="log-time">{{ l.at }}</span>
            <span class="log-content">{{ l.content }}</span>
          </div>
        </el-collapse-item>
          <el-collapse-item title="工具调用轨迹" name="tools">
            <div v-if="toolTraces.length === 0" class="log-empty">暂无工具调用记录</div>
            <div v-for="t in toolTraces" :key="`${t.executionVersion}-${t.sequenceNo}`" class="log-line tool-line">
              <el-tag size="small" :type="t.success ? 'success' : t.argumentValid ? 'danger' : 'warning'" effect="plain">
                {{ t.toolName }}
              </el-tag>
              <span class="log-time">v{{ t.executionVersion }} · {{ t.durationMs }}ms</span>
              <span class="log-content">
                {{ t.success ? '成功' : (t.argumentValid ? `失败（${t.errorCode ?? 'ERROR'}）` : '参数校验未通过') }}
                <span v-if="t.resultDigest" class="trace-digest">#{{ t.resultDigest }}</span>
              </span>
            </div>
          </el-collapse-item>
      </el-collapse>
    </div>

    <div v-if="streamingText" class="card">
      <h3 class="card-title">可选 AI 分析摘要</h3>
      <div class="streaming-text">{{ streamingText }}<span class="cursor">▍</span></div>
      <p class="hint">此为独立生成的分析摘要，非主 Agent 内部推理过程。</p>
    </div>

    <div v-if="report" class="card">
      <h3 class="card-title">尽调初审报告</h3>
      <div class="report">
        <div v-if="caseItem?.reportSource || caseItem?.snapshotId" class="report-row">
          <div class="report-label">执行溯源</div>
          <div class="trace-line">
            <span class="src" :class="caseItem?.reportSource === 'AGENT' ? 'src-agent' : 'src-rule'">
              {{ caseItem?.reportSource === 'AGENT' ? 'Agent 生成' : '规则降级' }}
            </span>
            <span v-if="caseItem?.snapshotId" class="mono-num trace-id">快照 {{ snapPrefix(caseItem.snapshotId) }}</span>
          </div>
        </div>

        <div class="report-row">
          <div class="report-label">风险评级</div>
          <span class="rk" :class="riskMeta[report.riskLevel]?.cls">{{ report.riskLevel }}</span>
          <span v-if="report.manualReviewRequired" class="need-review">需人工复核</span>
        </div>

        <div v-if="report.findingCodes?.length" class="report-row">
          <div class="report-label">风险发现代码</div>
          <div class="code-chips">
            <span v-for="c in report.findingCodes" :key="c" class="code-chip warn">{{ c }}</span>
          </div>
        </div>

        <div v-if="report.actionCodes?.length" class="report-row">
          <div class="report-label">处置代码</div>
          <div class="code-chips">
            <span
              v-for="c in report.actionCodes"
              :key="c"
              class="code-chip"
              :class="c === 'MANUAL_REVIEW' ? 'danger' : 'info'"
            >{{ c }}</span>
          </div>
        </div>

        <div class="report-row">
          <div class="report-label">风险点</div>
          <ul class="risk-list">
            <li v-for="(p, i) in report.riskPoints" :key="i">{{ p }}</li>
          </ul>
        </div>

        <div class="report-row">
          <div class="report-label">交易画像</div>
          <pre class="mono">{{ report.transactionProfile }}</pre>
        </div>

        <div class="report-row">
          <div class="report-label">股权穿透 / UBO</div>
          <pre class="mono">{{ report.corporateProfile }}</pre>
        </div>

        <div v-if="report.sanctions.length" class="report-row">
          <div class="report-label">黑名单命中</div>
          <ul class="risk-list sanction">
            <li v-for="(s, i) in report.sanctions" :key="i">{{ s }}</li>
          </ul>
        </div>

        <div v-if="report.legalBasis.length" class="report-row">
          <div class="report-label">法规依据（RAG）</div>
          <el-collapse>
            <el-collapse-item v-for="(b, i) in report.legalBasis" :key="i" :title="legalTitle(b)">
              <div class="legal-body">{{ legalBody(b) }}</div>
            </el-collapse-item>
          </el-collapse>
        </div>

        <div class="report-row">
          <div class="report-label">结论与建议</div>
          <div class="conclusion">{{ report.conclusion }}</div>
        </div>

        <div v-if="report.evidenceChain.length" class="report-row">
          <div class="report-label">证据链</div>
          <div class="evidence">
            <span v-for="(e, i) in report.evidenceChain" :key="i" class="ev-chip">{{ e }}</span>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="eddDialogOpen" title="提交补充尽调材料" width="760px">
      <div v-if="respondingEdd">
        <p class="hint">
          第 {{ respondingEdd.roundNo }} 轮 · 截止 {{ fmtDateTime(respondingEdd.dueAt) }}<br />
          需补充：{{ respondingEdd.requiredItems.map((item) => eddRequiredItemText[item] ?? item).join('、') }}
        </p>
        <el-form label-width="96px">
          <el-form-item label="材料说明">
            <el-input
              v-model="eddResponseSummary"
              type="textarea"
              :rows="5"
              maxlength="2000"
              show-word-limit
              placeholder="说明已核验的信息、结论及与原预警的关系（至少 10 个字符）"
            />
          </el-form-item>
          <el-form-item label="证据元数据">
            <div class="edd-evidence-list">
              <div v-for="(item, index) in eddEvidenceItems" :key="item.requiredItemCode" class="edd-evidence-row">
                <strong>{{ eddRequiredItemText[item.requiredItemCode] ?? item.requiredItemCode }}</strong>
                <el-select v-model="item.sourceSystem" placeholder="来源系统">
                  <el-option v-for="source in eddSourceSystemOptions" :key="source.value" :label="source.label" :value="source.value" />
                </el-select>
                <el-input v-model="item.sourceReference" :placeholder="`来源记录编号 ${index + 1}`" maxlength="128" />
                <el-input v-model="item.contentSha256" placeholder="文件或记录内容 SHA-256（64 位十六进制）" maxlength="64" class="mono-num" />
              </div>
            </div>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="eddDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="eddSubmitting" @click="submitEddResponse">提交并转复核</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="evidenceDialogOpen" title="关联调查证据" width="680px">
      <div v-if="evidenceHypothesis">
        <p class="hint">{{ evidenceHypothesis.title }} · 证据必须能回溯到冻结快照、业务系统记录或外部数据编号。</p>
        <el-form label-width="90px">
          <el-form-item label="证据类型">
            <el-select v-model="evidenceType" style="width: 100%">
              <el-option v-for="(label, value) in evidenceTypeText" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="证据方向">
            <el-radio-group v-model="evidenceStance">
              <el-radio value="SUPPORTS">支持假设</el-radio>
              <el-radio value="CONTRADICTS">反向证据</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="证据引用">
            <el-input v-model="evidenceReference" maxlength="160" placeholder="来源系统记录号、快照号或文档编号" />
          </el-form-item>
          <el-form-item label="调查摘要">
            <el-input v-model="evidenceSummary" type="textarea" :rows="5" maxlength="1000" show-word-limit
              placeholder="说明证据中的具体事实，以及它如何支持或反驳该假设（至少 10 个字符）" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="evidenceDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="evidenceSubmitting" @click="submitInvestigationEvidence">保存证据关联</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 0;
}

/* 加载态 / 错误态 */
.detail-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 80px 0;
  color: var(--text-dim);
  font-size: 14px;
}
.load-icon {
  font-size: 22px;
  color: var(--gold);
}
.detail-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 80px 0;
}
.err-mark {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 26px;
  color: var(--risk-high);
  border: 2px solid rgba(196, 61, 75, 0.4);
  background: rgba(196, 61, 75, 0.08);
}
.err-msg {
  margin: 0;
  color: var(--text-dim);
  font-size: 14px;
}

.detail-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 0;
  padding-bottom: 22px;
}

.bar-right {
  display: flex;
  gap: 10px;
  align-items: center;
}

.screening-title { display: flex; align-items: center; gap: 10px; }
.screening-meta { color: var(--text-faint); font-size: 12px; margin-bottom: 10px; }
.screening-empty { color: var(--risk-low); font-size: 13px; padding: 10px 0; }
.candidate-row {
  display: flex;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line-faint);
}
.candidate-row:last-child { border-bottom: none; }
.candidate-score {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-family: var(--font-mono);
  font-weight: 700;
  border: 1px solid var(--line);
}
.score-confirmed { color: var(--risk-high); background: rgba(196, 61, 75, 0.12); }
.score-review_required { color: var(--risk-mid); background: rgba(224, 162, 58, 0.12); }
.score-dismissed { color: var(--text-faint); background: rgba(124, 139, 163, 0.08); }
.candidate-main { flex: 1; min-width: 0; }
.candidate-head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.candidate-list { color: var(--text-faint); font-size: 12px; }
.candidate-explain { color: var(--text-dim); font-size: 13px; margin: 7px 0; line-height: 1.6; }
.candidate-review {
  color: var(--text-dim);
  font-size: 12px;
  line-height: 1.6;
  margin: 7px 0;
  padding: 7px 9px;
  border-left: 2px solid var(--gold);
  background: #f8fafc;
}
.candidate-foot { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; color: var(--text-faint); font-size: 11px; }
.candidate-actions { display: flex; gap: 6px; margin-top: 10px; flex-wrap: wrap; }
.operations-detail { margin-top: 14px; }
.ops-ok { color: var(--risk-low); }

.investigation-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.window-asof { color: var(--text-faint); font-size: 12px; font-weight: 400; }
.window-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.window-card { border: 1px solid var(--line-faint); border-radius: 8px; padding: 14px; background: #f8fafc; }
.window-metrics { display: grid; gap: 6px; margin-top: 10px; color: var(--text-dim); font-size: 12px; }
.currency-list { display: flex; flex-direction: column; gap: 4px; margin-top: 8px; font-size: 11px; color: var(--text-dim); }
.counterparty-list { display: flex; flex-direction: column; gap: 4px; border-top: 1px dashed var(--line); margin-top: 10px; padding-top: 8px; font-size: 11px; color: var(--text-dim); }
.investigation-subtitle { margin: 18px 0 10px; color: var(--text-dim); font-size: 14px; }
.hypothesis-list { display: flex; flex-direction: column; gap: 12px; }
.hypothesis-card { padding: 14px; border: 1px solid var(--line-faint); border-radius: 8px; background: #fff; }
.hypothesis-head { display: flex; justify-content: space-between; gap: 10px; align-items: center; }
.hypothesis-code { margin-left: 8px; font: 11px var(--font-mono); color: var(--text-faint); }
.investigation-question { margin: 8px 0; color: var(--text-dim); line-height: 1.7; }
.required-evidence { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.evidence-chain-list { display: flex; flex-direction: column; gap: 7px; margin-top: 10px; }
.evidence-chain-row { display: grid; grid-template-columns: 80px 90px minmax(130px, .7fr) 2fr; gap: 8px; align-items: start; padding: 8px; background: #f8fafc; font-size: 12px; line-height: 1.6; }
.hypothesis-rationale { border-left: 2px solid var(--gold); padding-left: 9px; color: var(--text-dim); line-height: 1.7; }
.hypothesis-actions { display: flex; gap: 7px; margin-top: 12px; }
.review-gates { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 16px; }

.rk {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 999px;
  display: inline-block;
}
.rk-high { color: var(--risk-high); background: #fef3f2; border: 1px solid #fecdca; }
.rk-mid { color: var(--risk-mid); background: #fffaeb; border: 1px solid #fedf89; }
.rk-low { color: var(--risk-low); background: #ecfdf3; border: 1px solid #abefc6; }

.snap, .time, .mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.snap { color: var(--text-dim); }
.time { color: var(--text-dim); font-size: 12px; }

.src {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 6px;
  border: 1px solid var(--line);
}
.src-agent { color: var(--text-dim); background: #f8fafc; }
.src-rule { color: var(--risk-mid); background: #fffaeb; }

/* 工作流 */
.sse-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 12px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-faint);
  vertical-align: middle;
}
.sse-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-faint);
}
.sse-indicator.open .sse-dot {
  background: var(--risk-low);
  box-shadow: 0 0 0 3px rgba(47, 163, 127, 0.18);
}
.sse-indicator.open { color: var(--risk-low); }
.sse-indicator.reconnecting .sse-dot {
  background: var(--risk-mid);
  box-shadow: 0 0 0 3px rgba(224, 162, 58, 0.18);
  animation: nodepulse 1.2s ease-in-out infinite;
}
.sse-indicator.reconnecting { color: var(--risk-mid); }

.flow {
  display: flex;
  gap: 0;
  margin-bottom: 14px;
  padding: 8px 0;
  overflow-x: auto;
}

.flow-node {
  position: relative;
  flex: 1;
  min-width: 96px;
  text-align: center;
  padding: 0 6px;
}

.node-circle {
  width: 34px;
  height: 34px;
  margin: 0 auto 8px;
  border-radius: 50%;
  border: 1px solid var(--line-strong);
  background: #ffffff;
  color: var(--text-faint);
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  position: relative;
  z-index: 2;
}

.flow-node.active .node-circle {
  border-color: var(--gold);
  color: var(--gold);
  box-shadow: 0 0 0 3px #dbeafe;
  animation: nodepulse 1.4s ease-in-out infinite;
}

.flow-node.done .node-circle {
  border-color: var(--risk-low);
  background: rgba(47, 163, 127, 0.16);
  color: var(--risk-low);
}

@keyframes nodepulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.08); }
}

.node-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.node-desc {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 3px;
  line-height: 1.4;
}

.node-link {
  position: absolute;
  top: 17px;
  left: 50%;
  width: 100%;
  height: 2px;
  background: var(--line);
  z-index: 1;
}
.node-link.done { background: rgba(47, 163, 127, 0.5); }

/* 日志 */
.log-empty { color: var(--text-faint); font-size: 13px; }
.log-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--line-faint);
  font-size: 13px;
}
.log-time {
  color: var(--text-faint);
  min-width: 78px;
  font-family: var(--font-mono);
  font-size: 12px;
}
.log-content {
  color: var(--text-dim);
  line-height: 1.7;
}

/* 报告 */
.report-row {
  display: flex;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line-faint);
}
.report-row:last-child { border-bottom: none; }
.report-label {
  width: 110px;
  flex-shrink: 0;
  font-weight: 600;
  color: var(--text-dim);
  font-size: 13px;
  padding-top: 2px;
  letter-spacing: 0.02em;
}
.need-review {
  font-size: 12px;
  color: #c43d4b;
  font-weight: 600;
  background: rgba(196, 61, 75, 0.12);
  border: 1px solid rgba(196, 61, 75, 0.3);
  border-radius: 999px;
  padding: 2px 12px;
}
.trace-line { display: flex; gap: 10px; align-items: center; }
.trace-id { font-size: 12px; color: var(--text-dim); }

.code-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.code-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 6px;
  border: 1px solid var(--line);
  color: var(--text-dim);
  background: #f8fafc;
}
.code-chip.warn { color: var(--risk-mid); border-color: rgba(224, 162, 58, 0.3); }
.code-chip.danger { color: var(--risk-high); border-color: rgba(196, 61, 75, 0.3); }
.code-chip.info { color: var(--risk-info); border-color: rgba(74, 158, 255, 0.3); }

.risk-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text);
  line-height: 1.9;
}
.risk-list.sanction li { color: var(--risk-high); }

.mono {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f8fafc;
  border: 1px solid var(--line-faint);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-dim);
  flex: 1;
}

.legal-body { line-height: 1.8; color: var(--text-dim); }
.conclusion { line-height: 1.8; color: var(--text); }

.evidence { display: flex; flex-wrap: wrap; gap: 8px; }
.evidence-link { cursor: help; color: var(--risk-info); }
.evidence-meta { max-width: 680px; line-height: 1.7; word-break: break-all; }
.overdue { color: var(--risk-high); font-weight: 700; }
.report-actions { display: flex; justify-content: flex-end; margin-top: 12px; }
.edd-evidence-list { width: 100%; display: flex; flex-direction: column; gap: 12px; }
.edd-evidence-row {
  display: grid;
  grid-template-columns: 180px 160px 1fr 1.5fr;
  gap: 10px;
  align-items: center;
  padding: 10px;
  border: 1px solid var(--line-faint);
  border-radius: 6px;
  background: #f8fafc;
}
.ev-chip {
  font-size: 12px;
  color: var(--text-dim);
  background: #f8fafc;
  border: 1px solid var(--line);
  padding: 3px 10px;
  border-radius: 6px;
}

.streaming-text {
  line-height: 1.9;
  color: var(--text);
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-all;
}
.cursor {
  color: var(--risk-info);
  animation: blink 1s step-end infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

@media (max-width: 860px) {
  .detail-bar { flex-wrap: wrap; }
  .report-row { flex-direction: column; gap: 6px; }
  .report-label { width: auto; }
  .edd-evidence-row { grid-template-columns: 1fr; }
  .window-grid, .review-gates { grid-template-columns: 1fr; }
  .evidence-chain-row { grid-template-columns: 1fr; }
}
</style>
