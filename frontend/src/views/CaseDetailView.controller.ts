import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
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
  submitEnhancedDueDiligence,
  reviewSanctionCandidate,
  screenSanctions,
  subscribeCase,
  type CaseItem,
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
import { Back, Download, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import { currentUser } from '../auth'
import { apiErrorMessage, isApiErrorStatus, isDialogCancellation } from '../utils/api-error'
import ExplanationWorkspace from './ExplanationWorkspace.vue'
import RefundPanel from './RefundPanel.vue'
import {
  eddSourceSystemOptions,
  eddStatusText,
  eddRequiredItemText,
  formatEddRequiredItems,
  legalBody,
  legalTitle,
  reportStatusText,
  reviewDecisionLabel,
  reviewDispositionText,
  screeningDecisionText,
  screeningTagType,
  snapPrefix,
} from './case-presenters'
import { useCaseInvestigation } from './useCaseInvestigation'

interface CaseDetailProps {
  readonly caseId: number
}

interface CaseDetailEmit {
  (event: 'back'): void
  (event: 'open-case', caseId: number): void
}

export function useCaseDetailViewController(props: CaseDetailProps, emit: CaseDetailEmit) {
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
    const targetCaseId = props.caseId
    const expectedConnectSeq = connectSeq
    try {
      const c = await getCase(targetCaseId)
      if (props.caseId !== targetCaseId || connectSeq !== expectedConnectSeq) return
      caseItem.value = c
      report.value = parseReport(c)
      loadError.value = false
    } catch {
      if (props.caseId !== targetCaseId || connectSeq !== expectedConnectSeq) return
      // 不静默吞错：标记错误，让页面展示"加载失败"态而非空白
      loadError.value = true
    }
    // SSE 终态到达后同步刷新调查就绪状态与运营待办，避免只刷新风险报告
    if (caseItem.value && ['HOLD', 'DONE', 'REPORT_PENDING', 'FAILED'].includes(caseItem.value.status)) {
      try {
        const [investigationView, windows, operations] = await Promise.all([
          getCaseInvestigation(targetCaseId),
          getTransactionWindows(targetCaseId),
          getCaseOperations(targetCaseId),
        ])
        if (props.caseId !== targetCaseId || connectSeq !== expectedConnectSeq) return
        investigation.value = investigationView
        transactionWindows.value = windows
        caseOperations.value = operations
        investigationLoadError.value = false
      } catch {
        if (props.caseId !== targetCaseId || connectSeq !== expectedConnectSeq) return
        investigationLoadError.value = true
      }
    }
  }

  async function connect() {
    const connectedCaseId = props.caseId
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
      connectedCaseId,
      (event) => {
        if (seq !== connectSeq || props.caseId !== connectedCaseId) return
        handleEvent(event)
      },
      (token) => {
        if (seq !== connectSeq || props.caseId !== connectedCaseId) return
        streamingText.value += token
      },
      (state) => {
        if (seq !== connectSeq || props.caseId !== connectedCaseId) return
        sseState.value = state
      },
      () => {
        if (seq !== connectSeq || props.caseId !== connectedCaseId) return
        pushLog('STREAM_PROTOCOL_ERROR', '实时事件格式无效，连接已关闭，请刷新页面重新对账', '')
      },
    )

    try {
      const c = await getCase(connectedCaseId)
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
      const history = await listLogs(connectedCaseId)
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
      const traces = await listToolTraces(connectedCaseId)
      if (seq !== connectSeq) return
      toolTraces.value = traces
    } catch {
      // 工具轨迹加载失败不影响主流程（日志/报告仍可查看）
      if (seq !== connectSeq) return
      toolTraces.value = []
    }

    try {
      const reviews = await listCaseReviews(connectedCaseId)
      if (seq !== connectSeq) return
      reviewHistory.value = reviews
    } catch {
      if (seq !== connectSeq) return
      reviewHistory.value = []
    }

    try {
      const requests = await listEnhancedDueDiligence(connectedCaseId)
      if (seq !== connectSeq) return
      eddRequests.value = requests
      eddHistoryLoadError.value = false
    } catch {
      if (seq !== connectSeq) return
      eddRequests.value = []
      eddHistoryLoadError.value = true
    }

    const [investigationResult, windowsResult, operationsResult] = await Promise.allSettled([
      getCaseInvestigation(connectedCaseId),
      getTransactionWindows(connectedCaseId),
      getCaseOperations(connectedCaseId),
    ])
    if (seq !== connectSeq) return
    investigation.value = investigationResult.status === 'fulfilled' ? investigationResult.value : null
    transactionWindows.value = windowsResult.status === 'fulfilled' ? windowsResult.value : null
    caseOperations.value = operationsResult.status === 'fulfilled' ? operationsResult.value : null
    investigationLoadError.value = investigationResult.status === 'rejected' || windowsResult.status === 'rejected'

    if (caseItem.value?.reviewDisposition === 'CONFIRM_SUSPICIOUS' && canReviewSanctions.value) {
      try {
        const reportItem = await getSuspiciousTransactionReport(connectedCaseId)
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
  watch(
    () => props.caseId,
    () => {
      eddDialogOpen.value = false
      respondingEdd.value = null
      returningReport.value = false
      reviewingFingerprint.value = ''
      resetInvestigationUi()
      void connect()
    },
  )

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
    const actionCaseId = props.caseId
    try {
      await retryCase(actionCaseId)
      if (props.caseId !== actionCaseId) return
      ElMessage.success('已重新入队，正在执行')
      await connect()
    } catch {
      ElMessage.error('人工重试失败，可能该工单不可重试，请刷新后重试')
    }
  }

  async function handleProcess() {
    const actionCaseId = props.caseId
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
    if (props.caseId !== actionCaseId) return
    try {
      await processCase(actionCaseId)
      if (props.caseId !== actionCaseId) return
      ElMessage.success('已请求开始调查（重复请求会由后端幂等去重）')
      await connect()
    } catch {
      ElMessage.error('开始调查失败，可能该案件非待处理状态（已入队或已开始），请刷新后重试')
    }
  }

  async function handleDossierDownload() {
    const actionCaseId = props.caseId
    dossierLoading.value = true
    try {
      const dossier = await getCaseDossier(actionCaseId)
      if (props.caseId !== actionCaseId) return
      const blob = new Blob([JSON.stringify(dossier, null, 2)], { type: 'application/json;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `aml-case-${actionCaseId}-dossier.json`
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

  const canRetry = computed(() => caseItem.value?.status === 'FAILED')
  const canProcess = computed(() => caseItem.value?.status === 'PENDING')
  const canReviewSanctions = computed(() => ['REVIEWER', 'ADMIN'].includes(currentUser.value?.role ?? ''))
  const canSubmitEdd = computed(() => ['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? ''))
  // 调查录入仅限待处理 / 人工处理中案件；DONE / REPORT_PENDING 只展示调查结果，不提供会被后端拒绝的编辑入口
  const canEditInvestigation = computed(
    () =>
      ['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '') &&
      !!caseItem.value &&
      ['PENDING', 'HOLD'].includes(caseItem.value.status),
  )
  /** 拆分入口与调查编辑解耦：容量超限等执行前失败的 FAILED 案件也允许拆分后受控恢复。 */
  const canSplitOnCase = computed(
    () =>
      ['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '') &&
      !!caseItem.value &&
      ['PENDING', 'FAILED'].includes(caseItem.value.status),
  )
  /** 调查契约 v1 案件的人工处理语义：调查未就绪 → 分析员待补齐；就绪 → 复核员决定。 */
  const holdPhaseHint = computed(() => {
    if (caseItem.value?.status !== 'HOLD' || (investigation.value?.contractVersion ?? 1) < 1) return null
    if (!investigation.value) return null
    return investigation.value.readyForFinalReview
      ? '自动分析完成，调查已就绪，等待复核员形成最终处置（确认可疑 / 排除预警）。'
      : '自动分析完成，待补齐调查（假设、证据与逐预警覆盖）后进入人工复核。'
  })

  async function handleCandidateReview(
    candidate: SanctionCandidateMatch,
    decision: 'CONFIRM' | 'DISMISS' | 'REQUEST_MORE_INFO',
  ) {
    const actionCaseId = props.caseId
    const customerId = caseItem.value?.customerId
    if (!customerId) return
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
      if (props.caseId !== actionCaseId) return
      reviewingFingerprint.value = candidate.candidateFingerprint
      const screening = await reviewSanctionCandidate(customerId, {
        candidateFingerprint: candidate.candidateFingerprint,
        decision,
        comment: value?.trim() ?? '',
        expectedRevision: candidate.reviewRevision,
      })
      if (props.caseId !== actionCaseId) return
      sanctionScreening.value = screening
      ElMessage.success('候选核验决定已保存')
    } catch (error: unknown) {
      if (isDialogCancellation(error)) return
      if (isApiErrorStatus(error, 409)) {
        if (props.caseId !== actionCaseId) return
        ElMessage.warning('候选已被其他复核人更新，正在刷新最新结果')
        const screening = await screenSanctions(customerId)
        if (props.caseId !== actionCaseId) return
        sanctionScreening.value = screening
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

  function openEddResponse(request: EnhancedDueDiligenceRequest) {
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
    const actionCaseId = props.caseId
    const request = respondingEdd.value
    if (!request) return
    if (eddResponseSummary.value.trim().length < 10) {
      ElMessage.warning('材料说明至少 10 个字符')
      return
    }
    const incomplete = eddEvidenceItems.value.some(
      (item) =>
        !item.sourceSystem || !item.sourceReference.trim() || !/^[a-fA-F0-9]{64}$/.test(item.contentSha256.trim()),
    )
    if (incomplete) {
      ElMessage.warning('请为每项材料填写来源记录编号和 64 位 SHA-256 摘要')
      return
    }
    eddSubmitting.value = true
    try {
      await submitEnhancedDueDiligence(actionCaseId, request.id, {
        expectedRevision: request.revision,
        responseSummary: eddResponseSummary.value.trim(),
        evidenceItems: eddEvidenceItems.value.map((item) => ({
          ...item,
          sourceReference: item.sourceReference.trim(),
          contentSha256: item.contentSha256.trim().toLowerCase(),
        })),
      })
      if (props.caseId !== actionCaseId) return
      ElMessage.success('补充尽调材料已提交，等待复核')
      eddDialogOpen.value = false
      const requests = await listEnhancedDueDiligence(actionCaseId)
      if (props.caseId !== actionCaseId) return
      eddRequests.value = requests
    } catch (error: unknown) {
      if (props.caseId !== actionCaseId) return
      ElMessage.error(apiErrorMessage(error, '补充尽调材料提交失败，请刷新后重试'))
    } finally {
      if (props.caseId === actionCaseId) eddSubmitting.value = false
    }
  }

  async function returnReportForCorrection() {
    const actionCaseId = props.caseId
    const reportToReturn = suspiciousReport.value
    if (reportToReturn?.status !== 'SUBMITTED') return
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
      if (props.caseId !== actionCaseId) return
      returningReport.value = true
      const returnedReport = await returnSuspiciousReport(actionCaseId, reportToReturn.revision, value.trim())
      if (props.caseId !== actionCaseId) return
      suspiciousReport.value = returnedReport
      await refresh()
      ElMessage.success('报告已退回补正，案件重新进入待报送')
    } catch (error: unknown) {
      if (isDialogCancellation(error)) return
      ElMessage.error(apiErrorMessage(error, '退回补正失败，请刷新后重试'))
    } finally {
      returningReport.value = false
    }
  }

  const {
    investigationScenarioText,
    evidenceTypeText,
    evidenceStanceText,
    hypothesisStatusText,
    coverageConclusionText,
    operationsPriorityText,
    operationsPhaseText,
    coverageFor,
    coverageGapFor,
    reloadInvestigationFacts,
    openInvestigationEvidence,
    submitInvestigationEvidence,
    investigationStale,
    decideInvestigationHypothesis,
    editAlertCoverage,
    reconfirmAlertCoverage,
    splitCaseAlert,
    resetInvestigationUi,
    formatAmount,
    reviewReasonText,
    coverageGapText,
  } = useCaseInvestigation({
    caseId: () => props.caseId,
    caseItem,
    investigation,
    transactionWindows,
    investigationLoadError,
    investigationActionId,
    evidenceDialogOpen,
    evidenceHypothesis,
    evidenceType,
    evidenceStance,
    evidenceReference,
    evidenceSummary,
    evidenceSubmitting,
    canEditInvestigation,
    connect,
    openCase: (caseId) => emit('open-case', caseId),
  })
  return {
    Back,
    canEditInvestigation,
    canProcess,
    canRetry,
    canReviewSanctions,
    canSplitOnCase,
    canSubmitEdd,
    caseItem,
    caseOperations,
    coverageConclusionText,
    coverageFor,
    coverageGapFor,
    coverageGapText,
    decideInvestigationHypothesis,
    detailLoading,
    dossierLoading,
    Download,
    eddDialogOpen,
    eddEvidenceItems,
    eddHistoryLoadError,
    eddRequests,
    eddRequiredItemText,
    eddResponseSummary,
    eddSourceSystemOptions,
    eddStatusText,
    eddSubmitting,
    editAlertCoverage,
    emit,
    evidenceDialogOpen,
    evidenceHypothesis,
    evidenceReference,
    evidenceStance,
    evidenceStanceText,
    evidenceSubmitting,
    evidenceSummary,
    evidenceType,
    evidenceTypeText,
    ExplanationWorkspace,
    fmtDateTime,
    formatAmount,
    formatEddRequiredItems,
    handleCandidateReview,
    handleDossierDownload,
    handleProcess,
    handleRetry,
    holdPhaseHint,
    hypothesisStatusText,
    investigation,
    investigationActionId,
    investigationLoadError,
    investigationScenarioText,
    investigationStale,
    isActive,
    isDone,
    legalBody,
    legalTitle,
    loadError,
    logOpen,
    logs,
    openEddResponse,
    openInvestigationEvidence,
    operationsPhaseText,
    operationsPriorityText,
    props,
    reconfirmAlertCoverage,
    RefreshRight,
    RefundPanel,
    reloadInvestigationFacts,
    report,
    reportStatusText,
    reportWorkflowLoadError,
    respondingEdd,
    retryLoad,
    returningReport,
    returnReportForCorrection,
    reviewDecisionLabel,
    reviewDispositionText,
    reviewHistory,
    reviewingFingerprint,
    reviewReasonText,
    riskMeta,
    sanctionScreening,
    screeningDecisionText,
    screeningLoading,
    screeningTagType,
    snapPrefix,
    splitCaseAlert,
    sseState,
    statusMeta,
    streamingText,
    submitEddResponse,
    submitInvestigationEvidence,
    suspiciousReport,
    toolTraces,
    transactionWindows,
    VideoPlay,
    workflowStages,
  }
}
