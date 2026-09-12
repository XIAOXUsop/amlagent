import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  fmtDateTime,
  cancelEnhancedDueDiligence,
  getCaseInvestigation,
  listEnhancedDueDiligenceAssignees,
  listEnhancedDueDiligence,
  listCaseOperations,
  listPendingReviews,
  listPendingSuspiciousReports,
  reviewStats,
  submitReview,
  getExplanationReviewBasis,
  reviewPrecheck,
  submitSuspiciousReport,
  type CaseItem,
  type CaseInvestigation,
  type CaseOperationsView,
  type CasePriority,
  type EnhancedDueDiligenceRequest,
  type SuspiciousTransactionReport,
} from '../api/client'
import { riskMeta } from '../constants/case'
import { Search, Stamp } from '@element-plus/icons-vue'
import { apiErrorMessage, isApiErrorStatus, isDialogCancellation } from '../utils/api-error'

export function useReviewViewController() {
  let reviewLoadVersion = 0
  let refreshVersion = 0
  let active = true
  const pending = ref<CaseItem[]>([])
  const stats = ref({
    reviewedCount: 0,
    agreementRate: 0,
    confirmedSuspiciousCount: 0,
    falsePositiveCount: 0,
    eddRequestedCount: 0,
  })
  const loading = ref(true)

  const reviewing = ref<CaseItem | null>(null)
  const dialogOpen = ref(false)
  const reviewerRiskLevel = ref('高风险')
  const decision = ref('CONFIRM_SUSPICIOUS')
  const reasonCode = ref('TRANSACTION_PATTERN_INCONSISTENT')
  const comment = ref('')
  const submitting = ref(false)
  const eddLoading = ref(false)
  const activeEdd = ref<EnhancedDueDiligenceRequest | null>(null)
  const reviewInvestigation = ref<CaseInvestigation | null>(null)
  const investigationLoadFailed = ref(false)
  const requiredItems = ref<string[]>([])
  const dueAt = ref('')
  const assignedTo = ref('')
  const assignedUnit = ref('反洗钱分析组')
  const assignees = ref<{ username: string; role: string }[]>([])
  const cancellingEdd = ref(false)
  const pendingReports = ref<SuspiciousTransactionReport[]>([])
  const operationsByCase = ref<Map<number, CaseOperationsView>>(new Map())
  const submittingReportId = ref<number | null>(null)

  const requiredItemOptions = [
    { value: 'CUSTOMER_IDENTITY', label: '客户身份及有效证件' },
    { value: 'BENEFICIAL_OWNER', label: '受益所有人及控制关系' },
    { value: 'SOURCE_OF_FUNDS', label: '资金来源证明' },
    { value: 'TRANSACTION_PURPOSE', label: '交易目的说明' },
    { value: 'COUNTERPARTY_RELATIONSHIP', label: '交易对手关系说明' },
    { value: 'SUPPORTING_CONTRACT_INVOICE', label: '合同、发票等业务凭证' },
    { value: 'WATCHLIST_IDENTITY', label: '名单身份核验材料' },
  ]

  const defaultItemsByReason: Record<string, string[]> = {
    MISSING_CUSTOMER_INFORMATION: ['CUSTOMER_IDENTITY'],
    SOURCE_OF_FUNDS_EVIDENCE_REQUIRED: ['SOURCE_OF_FUNDS', 'SUPPORTING_CONTRACT_INVOICE'],
    BENEFICIAL_OWNER_VERIFICATION_REQUIRED: ['BENEFICIAL_OWNER'],
    WATCHLIST_IDENTITY_VERIFICATION_REQUIRED: ['WATCHLIST_IDENTITY'],
  }

  const reasonsByDecision: Record<string, { value: string; label: string }[]> = {
    CONFIRM_SUSPICIOUS: [
      { value: 'TRANSACTION_PATTERN_INCONSISTENT', label: '交易模式与客户画像不一致' },
      { value: 'SANCTIONS_OR_WATCHLIST_MATCH', label: '制裁或关注名单命中' },
      { value: 'SOURCE_OF_FUNDS_UNCLEAR', label: '资金来源或用途不清' },
      { value: 'CUSTOMER_DUE_DILIGENCE_CONCERN', label: '客户尽调信息存在疑点' },
    ],
    EXCLUDE_FALSE_POSITIVE: [
      { value: 'VERIFIED_LEGITIMATE_PURPOSE', label: '已核实合理交易目的' },
      { value: 'CUSTOMER_PROFILE_CONSISTENT', label: '交易与客户画像一致' },
      { value: 'DUPLICATE_OR_KNOWN_ACTIVITY', label: '重复预警或已知正常活动' },
      { value: 'WATCHLIST_FALSE_POSITIVE', label: '名单同名或身份误匹配' },
    ],
    REQUEST_ENHANCED_DUE_DILIGENCE: [
      { value: 'MISSING_CUSTOMER_INFORMATION', label: '客户资料缺失' },
      { value: 'SOURCE_OF_FUNDS_EVIDENCE_REQUIRED', label: '需补充资金来源证明' },
      { value: 'BENEFICIAL_OWNER_VERIFICATION_REQUIRED', label: '需核实受益所有人' },
      { value: 'WATCHLIST_IDENTITY_VERIFICATION_REQUIRED', label: '需补充名单身份核验材料' },
    ],
  }

  const currentReasons = computed(() => reasonsByDecision[decision.value] ?? [])
  const activeEddTitle = computed(() => {
    const task = activeEdd.value
    if (!task) return ''
    const overdue = task.overdue ? '（已逾期）' : ''
    return `第 ${task.roundNo} 轮补充尽调待提交，承办人 ${task.assignedTo ?? '-'}，截止 ${fmtDateTime(task.dueAt)}${overdue}`
  })
  const currentInvestigationBlockers = computed(() => {
    if (decision.value === 'REQUEST_ENHANCED_DUE_DILIGENCE') return []
    if (investigationLoadFailed.value) return ['调查链路加载失败，无法安全作出最终处置']
    if (!reviewInvestigation.value) return []
    return decision.value === 'CONFIRM_SUSPICIOUS'
      ? reviewInvestigation.value.confirmSuspiciousBlockers
      : reviewInvestigation.value.excludeFalsePositiveBlockers
  })

  watch(decision, () => {
    reasonCode.value = currentReasons.value[0]?.value ?? ''
    if (decision.value === 'REQUEST_ENHANCED_DUE_DILIGENCE') {
      requiredItems.value = defaultItemsByReason[reasonCode.value] ?? ['CUSTOMER_IDENTITY']
      dueAt.value = defaultDueAt()
    }
  })

  watch(reasonCode, () => {
    if (decision.value === 'REQUEST_ENHANCED_DUE_DILIGENCE') {
      requiredItems.value = defaultItemsByReason[reasonCode.value] ?? ['CUSTOMER_IDENTITY']
    }
  })

  watch(dialogOpen, (open) => {
    if (!open) {
      reviewLoadVersion += 1
      eddLoading.value = false
    }
  })

  function defaultDueAt(): string {
    const date = new Date()
    date.setDate(date.getDate() + 5)
    const pad = (value: number) => String(value).padStart(2, '0')
    return (
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
      `T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
    )
  }

  onMounted(async () => {
    await refresh()
  })

  onUnmounted(() => {
    active = false
    reviewLoadVersion += 1
    refreshVersion += 1
  })

  async function refresh() {
    const requestVersion = ++refreshVersion
    loading.value = true
    try {
      const [p, s, users, reports, operations] = await Promise.all([
        listPendingReviews(),
        reviewStats(),
        listEnhancedDueDiligenceAssignees(),
        listPendingSuspiciousReports(),
        listCaseOperations(),
      ])
      if (!active || requestVersion !== refreshVersion) return
      operationsByCase.value = new Map(operations.map((item) => [item.caseId, item]))
      const order = new Map(operations.map((item, index) => [item.caseId, index]))
      pending.value = [...p].sort(
        (left, right) =>
          (order.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER),
      )
      stats.value = s
      assignees.value = users
      pendingReports.value = [...reports].sort(
        (left, right) =>
          (order.get(left.caseId) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.caseId) ?? Number.MAX_SAFE_INTEGER),
      )
      if (!assignedTo.value && users.length) assignedTo.value = users[0].username
    } catch {
      if (!active || requestVersion !== refreshVersion) return
      ElMessage.error('加载复核数据失败，请稍后重试')
    } finally {
      if (active && requestVersion === refreshVersion) loading.value = false
    }
  }

  async function openReview(row: CaseItem) {
    const loadVersion = ++reviewLoadVersion
    reviewing.value = row
    reviewerRiskLevel.value = row.riskLevel ?? '高风险'
    decision.value = 'CONFIRM_SUSPICIOUS'
    reasonCode.value = reasonsByDecision.CONFIRM_SUSPICIOUS[0].value
    comment.value = ''
    requiredItems.value = []
    dueAt.value = defaultDueAt()
    assignedTo.value = assignees.value[0]?.username ?? ''
    assignedUnit.value = '反洗钱分析组'
    activeEdd.value = null
    reviewInvestigation.value = null
    investigationLoadFailed.value = false
    dialogOpen.value = true
    eddLoading.value = true
    const [requestsResult, investigationResult] = await Promise.allSettled([
      listEnhancedDueDiligence(row.id),
      getCaseInvestigation(row.id),
    ])
    if (!active || loadVersion !== reviewLoadVersion || reviewing.value?.id !== row.id || !dialogOpen.value) return
    if (requestsResult.status === 'fulfilled') {
      const requests = requestsResult.value
      activeEdd.value = requests.length ? requests[requests.length - 1] : null
    } else {
      ElMessage.warning('补充尽调状态加载失败，请刷新后重试')
    }
    if (investigationResult.status === 'fulfilled') {
      reviewInvestigation.value = investigationResult.value
    } else {
      investigationLoadFailed.value = true
      ElMessage.warning('调查链路加载失败；最终处置已安全阻断，请刷新后重试')
    }
    if (loadVersion === reviewLoadVersion) eddLoading.value = false
  }

  const priorityLabels: Record<CasePriority, string> = {
    CRITICAL: '紧急',
    HIGH: '高',
    MEDIUM: '中',
    NORMAL: '常规',
  }

  function operationsFor(caseId: number): CaseOperationsView | undefined {
    return operationsByCase.value.get(caseId)
  }

  function priorityType(priority?: CasePriority): 'danger' | 'warning' | 'primary' | 'info' {
    if (priority === 'CRITICAL') return 'danger'
    if (priority === 'HIGH') return 'warning'
    if (priority === 'MEDIUM') return 'primary'
    return 'info'
  }

  function deadlineText(caseId: number) {
    const item = operationsFor(caseId)
    if (!item?.dueAt) return '-'
    return `${fmtDateTime(item.dueAt)}${item.overdue ? '（逾期）' : ''}`
  }

  async function doSubmit() {
    if (!reviewing.value || submitting.value) return
    if (activeEdd.value?.status === 'OPEN') {
      ElMessage.warning('当前补充尽调材料尚未提交，不能再次处置')
      return
    }
    if (currentInvestigationBlockers.value.length) {
      ElMessage.warning(`调查未闭环：${currentInvestigationBlockers.value.join('；')}`)
      return
    }
    if (comment.value.trim().length < 10) {
      ElMessage.warning('请记录具体分析过程，至少 10 个字符')
      return
    }
    if (
      decision.value === 'REQUEST_ENHANCED_DUE_DILIGENCE' &&
      (!requiredItems.value.length || !dueAt.value || !assignedTo.value || assignedUnit.value.trim().length < 2)
    ) {
      ElMessage.warning('请选择补充材料、承办人、承办部门并设置截止时间')
      return
    }
    const targetCase = reviewing.value
    const targetDecision = decision.value
    const targetReviewerRiskLevel = reviewerRiskLevel.value
    const targetReasonCode = reasonCode.value
    const targetComment = comment.value
    const targetRequiredItems = [...requiredItems.value]
    const targetDueAt = dueAt.value
    const targetAssignedTo = assignedTo.value
    const targetAssignedUnit = assignedUnit.value.trim()
    const operationVersion = reviewLoadVersion
    const isCurrentReview = () =>
      active && operationVersion === reviewLoadVersion && dialogOpen.value && reviewing.value?.id === targetCase.id
    submitting.value = true
    try {
      // v2 解释核验案件：最终复核必须携带服务端取号的依据令牌；决策表不通过时阻断并显示阻断项
      let reviewBasisToken: string | undefined
      if (
        (targetCase as { investigationContractVersion?: number }).investigationContractVersion === 2 &&
        targetDecision !== 'REQUEST_ENHANCED_DUE_DILIGENCE'
      ) {
        const basis = await getExplanationReviewBasis(targetCase.id)
        if (!isCurrentReview()) return
        reviewBasisToken = basis.reviewBasisToken
        const blockers = targetDecision === 'EXCLUDE_FALSE_POSITIVE' ? basis.excludeBlockers : basis.confirmBlockers
        if (
          (targetDecision === 'EXCLUDE_FALSE_POSITIVE' && !basis.canExclude) ||
          (targetDecision === 'CONFIRM_SUSPICIOUS' && !basis.canConfirm)
        ) {
          ElMessage.warning(`决策表未通过：${blockers.join('；') || '请先补齐单元提交与义务接续'}`)
          submitting.value = false
          return
        }
        // 复核预检（只读模拟，不创建任务）：暴露 token 陈旧与接续计划覆盖差异
        const precheck = await reviewPrecheck(targetCase.id, {
          decision: targetDecision,
          reviewBasisToken,
        })
        if (!isCurrentReview()) return
        if (!precheck.tokenCurrent) {
          ElMessage.warning(precheck.tokenProblem ?? '复核依据已变化，请刷新后重试')
          submitting.value = false
          return
        }
        if (precheck.uncoveredDecisionSupportTasks.length) {
          ElMessage.warning(
            `存在未接续的待补件任务（#${precheck.uncoveredDecisionSupportTasks.join('、#')}）；` +
              '请在复核中处理（补充尽调、确认可疑并接续或说明）',
          )
          submitting.value = false
          return
        }
      }
      await submitReview(targetCase.id, {
        reviewerRiskLevel: targetReviewerRiskLevel,
        decision: targetDecision,
        reasonCode: targetReasonCode,
        comment: targetComment,
        expectedReviewRevision: targetCase.reviewRevision ?? 0,
        reviewBasisToken,
        ...(targetDecision === 'REQUEST_ENHANCED_DUE_DILIGENCE'
          ? {
              requiredItems: targetRequiredItems,
              dueAt: new Date(targetDueAt).toISOString(),
              assignedTo: targetAssignedTo,
              assignedUnit: targetAssignedUnit,
            }
          : {}),
      })
      if (!isCurrentReview()) {
        void refresh()
        return
      }
      ElMessage.success('复核已提交')
      dialogOpen.value = false
      await refresh()
    } catch (e) {
      // 并发冲突（409）：另一方已复核，提示用户刷新查看，避免误以为成功
      if (isApiErrorStatus(e, 409)) {
        ElMessage.error('该工单已被其他人复核，状态已变化，请刷新查看最新信息')
      } else {
        ElMessage.error('提交失败，请稍后重试')
      }
    } finally {
      if (active && operationVersion === reviewLoadVersion) submitting.value = false
    }
  }

  async function cancelActiveEdd() {
    if (!reviewing.value || activeEdd.value?.status !== 'OPEN' || cancellingEdd.value) return
    const targetCaseId = reviewing.value.id
    const targetEdd = activeEdd.value
    const operationVersion = reviewLoadVersion
    try {
      const { value } = await ElMessageBox.prompt('撤销后案件仍保持待复核，请说明原因。', '撤销补充尽调', {
        inputType: 'textarea',
        inputPlaceholder: '说明误发、需求变化或其他撤销原因（至少 10 个字符）',
        inputValidator: (text: string) => text?.trim().length >= 10 || '撤销原因至少 10 个字符',
      })
      if (
        !active ||
        operationVersion !== reviewLoadVersion ||
        reviewing.value?.id !== targetCaseId ||
        activeEdd.value?.id !== targetEdd.id
      )
        return
      cancellingEdd.value = true
      const cancelled = await cancelEnhancedDueDiligence(targetCaseId, targetEdd.id, {
        expectedRevision: targetEdd.revision,
        reason: value.trim(),
      })
      if (
        !active ||
        operationVersion !== reviewLoadVersion ||
        reviewing.value?.id !== targetCaseId ||
        activeEdd.value?.id !== targetEdd.id
      )
        return
      activeEdd.value = cancelled
      ElMessage.success('补充尽调任务已撤销，可重新处置案件')
    } catch (error: unknown) {
      if (isDialogCancellation(error)) return
      ElMessage.error(apiErrorMessage(error, '撤销失败，请刷新后重试'))
    } finally {
      if (active && operationVersion === reviewLoadVersion) cancellingEdd.value = false
    }
  }

  async function markReportSubmitted(report: SuspiciousTransactionReport) {
    if (submittingReportId.value !== null) return
    try {
      const { value } = await ElMessageBox.prompt('请填写外部可疑交易报告系统返回的受理编号。', '登记报送完成', {
        inputPlaceholder: '例如 STR-2026-000123',
        inputValidator: (text: string) =>
          /^[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}$/.test(text?.trim()) || '请输入至少 3 位有效受理编号',
      })
      if (!active) return
      submittingReportId.value = report.id
      await submitSuspiciousReport(report.caseId, report.revision, value.trim())
      if (!active || submittingReportId.value !== report.id) return
      ElMessage.success('已登记报送完成，案件正式结束')
      await refresh()
    } catch (error: unknown) {
      if (!active) return
      if (isDialogCancellation(error)) return
      ElMessage.error(apiErrorMessage(error, '登记报送失败，请刷新后重试'))
    } finally {
      if (active && submittingReportId.value === report.id) submittingReportId.value = null
    }
  }

  return {
    activeEdd,
    activeEddTitle,
    assignedTo,
    assignedUnit,
    assignees,
    cancelActiveEdd,
    cancellingEdd,
    comment,
    currentInvestigationBlockers,
    currentReasons,
    deadlineText,
    decision,
    dialogOpen,
    doSubmit,
    dueAt,
    eddLoading,
    fmtDateTime,
    loading,
    markReportSubmitted,
    openReview,
    operationsFor,
    pending,
    pendingReports,
    priorityLabels,
    priorityType,
    reasonCode,
    requiredItemOptions,
    requiredItems,
    reviewerRiskLevel,
    reviewing,
    riskMeta,
    Search,
    Stamp,
    stats,
    submitting,
    submittingReportId,
  }
}
