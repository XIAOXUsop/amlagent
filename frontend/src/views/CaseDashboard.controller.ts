import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  closeDuplicateAlert,
  createAmlAlert,
  createCase,
  createCaseFromAlert,
  fmtDateTime,
  linkAlertToCase,
  listAlertCandidateCases,
  listAlertInbox,
  listCases,
  listCaseStats,
  listCustomers,
  listCaseOperations,
  listPendingEnhancedDueDiligence,
  processCase,
  retryCase,
  type AmlAlert,
  type CaseItem,
  type CaseStats,
  type Customer,
  type EnhancedDueDiligenceRequest,
  type InvestigationScenarioCode,
  type CaseOperationsView,
  type CasePriority,
  type OperationPhase,
} from '../api/client'
import { riskMeta, statusMeta } from '../constants/case'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { currentUser } from '../auth'
import { apiErrorMessage, isDialogCancellation } from '../utils/api-error'

export function useCaseDashboardController(emit: (event: 'open-case', caseId: number) => void) {
  let listRequestSequence = 0
  let operationsRequestSequence = 0
  let statsRequestSequence = 0
  let alertsRequestSequence = 0
  let eddRequestSequence = 0
  let customersRequestSequence = 0
  let active = true
  const cases = ref<CaseItem[]>([])
  const customers = ref<Customer[]>([])
  const selectedCustomer = ref<string>('')
  const alertRule = ref('大额频繁跨国转账、夜间集中交易')
  const loading = ref(false)
  const listLoading = ref(false)
  const processingId = ref<number | null>(null)
  const page = ref(0)
  const total = ref(0)
  const pageSize = 10
  const stats = ref<CaseStats | null>(null)
  const eddTasks = ref<EnhancedDueDiligenceRequest[]>([])
  const eddLoadError = ref(false)
  const alertInbox = ref<AmlAlert[]>([])
  const alertInboxError = ref(false)
  const alertSubmitting = ref(false)
  const alertExternalId = ref(`ALT-${Date.now()}`)
  const alertRuleCode = ref('CROSS_BORDER_NIGHT_ACTIVITY')
  const alertScenarioCode = ref<InvestigationScenarioCode>('CROSS_BORDER_ANOMALY')
  const alertHitReason = ref('客户短期出现多笔夜间跨境交易，与历史经营活动不一致')
  const triagingAlertId = ref<number | null>(null)
  const operations = ref<CaseOperationsView[]>([])
  const operationsLoading = ref(false)
  const operationsError = ref(false)
  const operationsOverdueOnly = ref(false)
  const operationsPriority = ref<CasePriority | ''>('')
  const operationsPhase = ref<OperationPhase | ''>('')

  const scenarioOptions: { value: InvestigationScenarioCode; label: string }[] = [
    { value: 'STRUCTURING', label: '拆分交易规避监测' },
    { value: 'RAPID_MOVEMENT', label: '资金快进快出' },
    { value: 'CROSS_BORDER_ANOMALY', label: '异常跨境交易' },
    { value: 'PROFILE_MISMATCH', label: '交易与客户画像不匹配' },
    { value: 'COMPLEX_OWNERSHIP', label: '复杂受益所有权' },
    { value: 'SANCTIONS_WATCHLIST', label: '名单身份核验' },
  ]

  // 态势概览：来自后端全量统计接口（跨分页），不再以当前页数据冒充全局数字
  const overview = computed(() => {
    const s = stats.value
    return {
      total: s?.total ?? total.value,
      pending: s?.pending ?? 0,
      running: s?.running ?? 0,
      hold: s?.hold ?? 0,
      reportPending: s?.reportPending ?? 0,
      done: s?.done ?? 0,
    }
  })

  onMounted(() => {
    void refresh()
    void loadStats()
    void loadEddTasks()
    void loadAlertInbox()
    void loadOperations()
    void loadCustomers()
  })

  onUnmounted(() => {
    active = false
    listRequestSequence += 1
    operationsRequestSequence += 1
    statsRequestSequence += 1
    alertsRequestSequence += 1
    eddRequestSequence += 1
    customersRequestSequence += 1
  })

  async function loadCustomers() {
    const requestSequence = ++customersRequestSequence
    try {
      const result = await listCustomers()
      if (!active || requestSequence !== customersRequestSequence) return
      customers.value = result
      if (!selectedCustomer.value && result.length > 0) selectedCustomer.value = result[0].id
    } catch {
      if (active && requestSequence === customersRequestSequence) {
        ElMessage.error('客户列表加载失败，暂时无法创建工单')
      }
    }
  }

  async function loadStats() {
    const requestSequence = ++statsRequestSequence
    try {
      const result = await listCaseStats()
      if (!active || requestSequence !== statsRequestSequence) return
      stats.value = result
    } catch {
      if (active && requestSequence === statsRequestSequence) stats.value = null
    }
  }

  async function refresh() {
    const requestSequence = ++listRequestSequence
    const requestedPage = page.value
    listLoading.value = true
    try {
      const p = await listCases(requestedPage, pageSize)
      if (!active || requestSequence !== listRequestSequence || page.value !== requestedPage) return
      cases.value = p.content
      total.value = p.totalElements
    } catch {
      if (!active || requestSequence !== listRequestSequence || page.value !== requestedPage) return
      ElMessage.error('工单列表加载失败，请稍后重试')
    } finally {
      if (active && requestSequence === listRequestSequence) listLoading.value = false
    }
  }

  function onPageChange(p: number) {
    page.value = p - 1
    void refresh()
  }

  async function handleCreate(autoProcess: boolean) {
    if (loading.value) return
    if (!selectedCustomer.value) {
      ElMessage.warning('请选择客户')
      return
    }
    if (!alertRule.value.trim()) {
      ElMessage.warning('请填写预警规则描述')
      return
    }
    loading.value = true
    try {
      const c = await createCase(selectedCustomer.value, alertRule.value.trim(), { autoProcess })
      if (!active) return
      if (autoProcess) {
        ElMessage.success(`工单 #${c.id} 创建成功，已开始尽调`)
        // 创建成功后的主路径是进入新工单；不要让列表与运营队列刷新阻塞页面跳转。
        // 用户返回工作台时组件会重新挂载并获取最新数据。
        emit('open-case', c.id)
      } else {
        // 暂不启动：保持 PENDING 以便继续归并同客户其他预警，稍后在详情页显式开始调查。
        ElMessage.success(`工单 #${c.id} 已创建，可继续归并同客户预警，稍后开始调查`)
        await Promise.allSettled([refresh(), loadOperations()])
        void loadStats()
        emit('open-case', c.id)
      }
    } catch {
      if (!active) return
      ElMessage.error('创建工单失败，请检查客户与预警规则后重试')
    } finally {
      if (active) loading.value = false
    }
  }

  async function handleProcess(row: CaseItem) {
    if (processingId.value !== null) return
    processingId.value = row.id
    try {
      if (row.status === 'FAILED') {
        await retryCase(row.id)
        if (!active) return
        ElMessage.success('已重新入队，正在执行')
      } else {
        await processCase(row.id)
        if (!active) return
      }
      emit('open-case', row.id)
    } catch {
      if (!active) return
      ElMessage.error(row.status === 'FAILED' ? '重试失败，请稍后重试' : '触发尽调失败')
    } finally {
      if (active && processingId.value === row.id) processingId.value = null
    }
  }

  function srcTag(row: CaseItem) {
    if (!row.reportSource) return null
    return row.reportSource === 'AGENT' ? 'AGENT' : '规则降级'
  }

  async function loadOperations() {
    const requestSequence = ++operationsRequestSequence
    const filters = {
      overdueOnly: operationsOverdueOnly.value,
      priority: operationsPriority.value,
      phase: operationsPhase.value,
    }
    operationsLoading.value = true
    try {
      const result = await listCaseOperations(filters)
      if (!active || requestSequence !== operationsRequestSequence) return
      operations.value = result
      operationsError.value = false
    } catch {
      if (!active || requestSequence !== operationsRequestSequence) return
      operations.value = []
      operationsError.value = true
    } finally {
      if (active && requestSequence === operationsRequestSequence) operationsLoading.value = false
    }
  }

  const priorityText: Record<CasePriority, string> = {
    CRITICAL: '紧急',
    HIGH: '高',
    MEDIUM: '中',
    NORMAL: '常规',
  }
  const phaseText: Record<OperationPhase, string> = {
    INVESTIGATION: '案件调查',
    REVIEW: '人工复核',
    ENHANCED_DUE_DILIGENCE: '补充尽调',
    REPORTING: '可疑报告报送',
    COMPLETED: '已完成',
  }

  function priorityTag(priority: CasePriority): 'danger' | 'warning' | 'primary' | 'info' {
    if (priority === 'CRITICAL') return 'danger'
    if (priority === 'HIGH') return 'warning'
    if (priority === 'MEDIUM') return 'primary'
    return 'info'
  }

  function remainingText(item: CaseOperationsView) {
    const minutes = Math.abs(item.minutesRemaining)
    const days = Math.floor(minutes / 1440)
    const hours = Math.floor((minutes % 1440) / 60)
    const duration = days ? `${days}天${hours}小时` : `${hours}小时${minutes % 60}分钟`
    return item.overdue ? `已逾期 ${duration}` : `剩余 ${duration}`
  }

  async function loadAlertInbox() {
    if (!['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '')) return
    const requestSequence = ++alertsRequestSequence
    try {
      const result = await listAlertInbox()
      if (!active || requestSequence !== alertsRequestSequence) return
      alertInbox.value = result
      alertInboxError.value = false
    } catch {
      if (active && requestSequence === alertsRequestSequence) alertInboxError.value = true
    }
  }

  async function createInboxAlert() {
    if (alertSubmitting.value) return
    if (!selectedCustomer.value) {
      ElMessage.warning('请选择客户')
      return
    }
    alertSubmitting.value = true
    try {
      await createAmlAlert({
        externalAlertId: alertExternalId.value.trim(),
        customerId: selectedCustomer.value,
        ruleCode: alertRuleCode.value.trim(),
        scenarioCode: alertScenarioCode.value,
        hitReason: alertHitReason.value.trim(),
      })
      if (!active) return
      alertExternalId.value = `ALT-${Date.now()}`
      ElMessage.success('预警已进入待分诊队列')
      await loadAlertInbox()
    } catch (error: unknown) {
      if (!active) return
      ElMessage.error(apiErrorMessage(error, '预警创建失败，请检查编号和字段'))
    } finally {
      if (active) alertSubmitting.value = false
    }
  }

  async function createAlertCase(alert: AmlAlert, autoProcess: boolean) {
    if (triagingAlertId.value !== null) return
    triagingAlertId.value = alert.id
    try {
      const created = await createCaseFromAlert(alert.id, alert.revision, { autoProcess })
      if (!active) return
      if (autoProcess) {
        ElMessage.success(`已创建案件 #${created.id} 并开始调查`)
      } else {
        ElMessage.success(`案件 #${created.id} 已创建，可继续归并同客户预警，稍后开始调查`)
      }
      await Promise.all([loadAlertInbox(), refresh(), loadOperations()])
      void loadStats()
      emit('open-case', created.id)
    } catch (error: unknown) {
      if (!active) return
      ElMessage.error(apiErrorMessage(error, '预警建案失败，请刷新后重试'))
    } finally {
      if (active && triagingAlertId.value === alert.id) triagingAlertId.value = null
    }
  }

  async function mergeAlert(alert: AmlAlert) {
    if (triagingAlertId.value !== null) return
    triagingAlertId.value = alert.id
    try {
      const candidates = await listAlertCandidateCases(alert.id)
      if (!active || triagingAlertId.value !== alert.id) return
      if (!candidates.length) {
        ElMessage.warning('该客户暂无尚未开始调查的候选案件，请直接建案')
        return
      }
      const candidateText = candidates.map((item) => `#${item.id} ${item.alertRule}`).join('\n')
      const { value: caseValue } = await ElMessageBox.prompt(`同客户候选案件：\n${candidateText}`, '选择归并案件', {
        inputPlaceholder: '输入案件编号',
        inputValidator: (text: string) =>
          candidates.some((item) => item.id === Number(text?.trim())) || '请输入候选列表中的案件编号',
      })
      if (!active || triagingAlertId.value !== alert.id) return
      const { value: reason } = await ElMessageBox.prompt(
        '说明这些预警为什么应作为同一客户整体行为调查。',
        '记录归并依据',
        {
          inputType: 'textarea',
          inputValidator: (text: string) => text?.trim().length >= 10 || '归并依据至少 10 个字符',
        },
      )
      if (!active || triagingAlertId.value !== alert.id) return
      await linkAlertToCase(alert.id, Number(caseValue.trim()), alert.revision, reason.trim())
      if (!active || triagingAlertId.value !== alert.id) return
      ElMessage.success('预警已归并到案件')
      await Promise.all([loadAlertInbox(), refresh(), loadOperations()])
    } catch (error: unknown) {
      if (!active) return
      if (isDialogCancellation(error)) return
      ElMessage.error(apiErrorMessage(error, '预警归并失败，请刷新后重试'))
    } finally {
      if (active && triagingAlertId.value === alert.id) triagingAlertId.value = null
    }
  }

  async function closeAsDuplicate(alert: AmlAlert) {
    if (triagingAlertId.value !== null) return
    triagingAlertId.value = alert.id
    try {
      const { value } = await ElMessageBox.prompt(
        '重复关闭不会进入案件调查，请说明对应的原预警或重复判断依据。',
        '关闭重复预警',
        {
          inputType: 'textarea',
          inputValidator: (text: string) => text?.trim().length >= 10 || '重复判断依据至少 10 个字符',
        },
      )
      if (!active || triagingAlertId.value !== alert.id) return
      await closeDuplicateAlert(alert.id, alert.revision, value.trim())
      if (!active || triagingAlertId.value !== alert.id) return
      ElMessage.success('预警已按重复项关闭')
      await loadAlertInbox()
    } catch (error: unknown) {
      if (!active) return
      if (isDialogCancellation(error)) return
      ElMessage.error(apiErrorMessage(error, '关闭预警失败，请刷新后重试'))
    } finally {
      if (active && triagingAlertId.value === alert.id) triagingAlertId.value = null
    }
  }

  function scenarioName(code: string) {
    return scenarioOptions.find((item) => item.value === code)?.label ?? code
  }

  async function loadEddTasks() {
    if (!['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '')) return
    const requestSequence = ++eddRequestSequence
    try {
      const result = await listPendingEnhancedDueDiligence(currentUser.value?.role === 'ADMIN')
      if (!active || requestSequence !== eddRequestSequence) return
      eddTasks.value = result
      eddLoadError.value = false
    } catch {
      if (active && requestSequence === eddRequestSequence) eddLoadError.value = true
    }
  }

  const dispositionText: Record<string, string> = {
    CONFIRM_SUSPICIOUS: '确认可疑',
    EXCLUDE_FALSE_POSITIVE: '排除预警',
    REQUEST_ENHANCED_DUE_DILIGENCE: '补充尽调',
  }

  const eddRequiredItemText: Record<string, string> = {
    CUSTOMER_IDENTITY: '客户身份',
    BENEFICIAL_OWNER: '受益所有人',
    SOURCE_OF_FUNDS: '资金来源',
    TRANSACTION_PURPOSE: '交易目的',
    COUNTERPARTY_RELATIONSHIP: '交易对手关系',
    SUPPORTING_CONTRACT_INVOICE: '合同/发票',
    WATCHLIST_IDENTITY: '名单身份核验',
  }

  return {
    alertExternalId,
    alertHitReason,
    alertInbox,
    alertInboxError,
    alertRule,
    alertRuleCode,
    alertScenarioCode,
    alertSubmitting,
    cases,
    closeAsDuplicate,
    createAlertCase,
    createInboxAlert,
    currentUser,
    customers,
    dispositionText,
    eddLoadError,
    eddRequiredItemText,
    eddTasks,
    fmtDateTime,
    handleCreate,
    handleProcess,
    listLoading,
    loading,
    loadOperations,
    mergeAlert,
    onPageChange,
    operations,
    operationsError,
    operationsLoading,
    operationsOverdueOnly,
    operationsPhase,
    operationsPriority,
    overview,
    page,
    pageSize,
    phaseText,
    Plus,
    priorityTag,
    priorityText,
    processingId,
    Refresh,
    refresh,
    remainingText,
    riskMeta,
    scenarioName,
    scenarioOptions,
    Search,
    selectedCustomer,
    srcTag,
    statusMeta,
    total,
    triagingAlertId,
  }
}
