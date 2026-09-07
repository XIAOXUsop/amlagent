<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  closeDuplicateAlert, createAmlAlert, createCase, createCaseFromAlert, fmtDateTime,
  linkAlertToCase, listAlertCandidateCases, listAlertInbox, listCases, listCaseStats, listCustomers,
  listCaseOperations, listPendingEnhancedDueDiligence, processCase, retryCase,
  type AmlAlert, type CaseItem, type CaseStats, type Customer, type EnhancedDueDiligenceRequest,
  type InvestigationScenarioCode, type CaseOperationsView, type CasePriority, type OperationPhase,
} from '../api/client'
import { riskMeta, statusMeta } from '../constants/case'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { currentUser } from '../auth'

const emit = defineEmits<{ (e: 'open-case', id: number): void }>()

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

const scenarioOptions: Array<{ value: InvestigationScenarioCode; label: string }> = [
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

onMounted(async () => {
  await refresh()
  loadStats()
  loadEddTasks()
  loadAlertInbox()
  loadOperations()
  customers.value = await listCustomers()
  if (customers.value.length > 0) {
    selectedCustomer.value = customers.value[0].id
  }
})

async function loadStats() {
  try {
    stats.value = await listCaseStats()
  } catch {
    stats.value = null
  }
}

async function refresh() {
  listLoading.value = true
  try {
    const p = await listCases(page.value, pageSize)
    cases.value = p.content
    total.value = p.totalElements
  } catch {
    ElMessage.error('工单列表加载失败，请稍后重试')
  } finally {
    listLoading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p - 1
  refresh()
}

async function handleCreate(autoProcess: boolean) {
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
    if (autoProcess) {
      ElMessage.success(`工单 #${c.id} 创建成功，已开始尽调`)
      // 创建成功后的主路径是进入新工单；不要让列表与运营队列刷新阻塞页面跳转。
      // 用户返回工作台时组件会重新挂载并获取最新数据。
      emit('open-case', c.id)
    } else {
      // 暂不启动：保持 PENDING 以便继续归并同客户其他预警，稍后在详情页显式开始调查。
      ElMessage.success(`工单 #${c.id} 已创建，可继续归并同客户预警，稍后开始调查`)
      await Promise.allSettled([refresh(), loadOperations()])
      loadStats()
      emit('open-case', c.id)
    }
  } catch {
    ElMessage.error('创建工单失败，请检查客户与预警规则后重试')
  } finally {
    loading.value = false
  }
}

async function handleProcess(row: CaseItem) {
  processingId.value = row.id
  try {
    if (row.status === 'FAILED') {
      await retryCase(row.id)
      ElMessage.success('已重新入队，正在执行')
    } else {
      await processCase(row.id)
    }
    emit('open-case', row.id)
  } catch {
    ElMessage.error(row.status === 'FAILED' ? '重试失败，请稍后重试' : '触发尽调失败')
  } finally {
    processingId.value = null
  }
}

function srcTag(row: CaseItem) {
  if (!row.reportSource) return null
  return row.reportSource === 'AGENT' ? 'AGENT' : '规则降级'
}

async function loadOperations() {
  operationsLoading.value = true
  try {
    operations.value = await listCaseOperations({
      overdueOnly: operationsOverdueOnly.value,
      priority: operationsPriority.value,
      phase: operationsPhase.value,
    })
    operationsError.value = false
  } catch {
    operations.value = []
    operationsError.value = true
  } finally {
    operationsLoading.value = false
  }
}

const priorityText: Record<CasePriority, string> = {
  CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', NORMAL: '常规',
}
const phaseText: Record<OperationPhase, string> = {
  INVESTIGATION: '案件调查', REVIEW: '人工复核',
  ENHANCED_DUE_DILIGENCE: '补充尽调', REPORTING: '可疑报告报送', COMPLETED: '已完成',
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
  try {
    alertInbox.value = await listAlertInbox()
    alertInboxError.value = false
  } catch {
    alertInboxError.value = true
  }
}

async function createInboxAlert() {
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
    alertExternalId.value = `ALT-${Date.now()}`
    ElMessage.success('预警已进入待分诊队列')
    await loadAlertInbox()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '预警创建失败，请检查编号和字段')
  } finally {
    alertSubmitting.value = false
  }
}

async function createAlertCase(alert: AmlAlert, autoProcess: boolean) {
  triagingAlertId.value = alert.id
  try {
    const created = await createCaseFromAlert(alert.id, alert.revision, { autoProcess })
    if (autoProcess) {
      ElMessage.success(`已创建案件 #${created.id} 并开始调查`)
    } else {
      ElMessage.success(`案件 #${created.id} 已创建，可继续归并同客户预警，稍后开始调查`)
    }
    await Promise.all([loadAlertInbox(), refresh(), loadOperations()])
    loadStats()
    emit('open-case', created.id)
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '预警建案失败，请刷新后重试')
  } finally {
    triagingAlertId.value = null
  }
}

async function mergeAlert(alert: AmlAlert) {
  triagingAlertId.value = alert.id
  try {
    const candidates = await listAlertCandidateCases(alert.id)
    if (!candidates.length) {
      ElMessage.warning('该客户暂无尚未开始调查的候选案件，请直接建案')
      return
    }
    const candidateText = candidates.map((item) => `#${item.id} ${item.alertRule}`).join('\n')
    const { value: caseValue } = await ElMessageBox.prompt(
      `同客户候选案件：\n${candidateText}`, '选择归并案件', {
        inputPlaceholder: '输入案件编号',
        inputValidator: (text: string) => candidates.some((item) => item.id === Number(text?.trim()))
          || '请输入候选列表中的案件编号',
      },
    )
    const { value: reason } = await ElMessageBox.prompt(
      '说明这些预警为什么应作为同一客户整体行为调查。', '记录归并依据', {
        inputType: 'textarea',
        inputValidator: (text: string) => text?.trim().length >= 10 || '归并依据至少 10 个字符',
      },
    )
    await linkAlertToCase(alert.id, Number(caseValue.trim()), alert.revision, reason.trim())
    ElMessage.success('预警已归并到案件')
    await Promise.all([loadAlertInbox(), refresh(), loadOperations()])
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message ?? '预警归并失败，请刷新后重试')
  } finally {
    triagingAlertId.value = null
  }
}

async function closeAsDuplicate(alert: AmlAlert) {
  triagingAlertId.value = alert.id
  try {
    const { value } = await ElMessageBox.prompt(
      '重复关闭不会进入案件调查，请说明对应的原预警或重复判断依据。', '关闭重复预警', {
        inputType: 'textarea',
        inputValidator: (text: string) => text?.trim().length >= 10 || '重复判断依据至少 10 个字符',
      },
    )
    await closeDuplicateAlert(alert.id, alert.revision, value.trim())
    ElMessage.success('预警已按重复项关闭')
    await loadAlertInbox()
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message ?? '关闭预警失败，请刷新后重试')
  } finally {
    triagingAlertId.value = null
  }
}

function scenarioName(code: string) {
  return scenarioOptions.find((item) => item.value === code)?.label ?? code
}

async function loadEddTasks() {
  if (!['ANALYST', 'ADMIN'].includes(currentUser.value?.role ?? '')) return
  try {
    eddTasks.value = await listPendingEnhancedDueDiligence(currentUser.value?.role === 'ADMIN')
    eddLoadError.value = false
  } catch {
    eddLoadError.value = true
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
</script>

<template>
  <div class="dashboard">
    <header class="page-intro">
      <h2>工单</h2>
      <p>创建预警、跟踪 Agent 调查进度，并进入人工处置。</p>
    </header>
    <!-- 态势概览 -->
    <div class="overview card">
      <div class="ov-total">
        <span class="ov-label">工单总数</span>
        <b class="mono-num">{{ overview.total }}</b>
        <span class="ov-sub">含历史工单</span>
      </div>
      <div class="ov-grid">
        <div class="ov-cell">
          <i class="dot" style="background: #64748b"></i>
          <div><b class="mono-num">{{ overview.pending }}</b><span>待处理</span></div>
        </div>
        <div class="ov-cell">
          <i class="dot pulse" style="background: #e0a23a"></i>
          <div><b class="mono-num">{{ overview.running }}</b><span>执行中</span></div>
        </div>
        <div class="ov-cell">
          <i class="dot" style="background: #c43d4b"></i>
          <div><b class="mono-num">{{ overview.hold }}</b><span>转人工</span></div>
        </div>
        <div class="ov-cell">
          <i class="dot" style="background: #d97706"></i>
          <div><b class="mono-num">{{ overview.reportPending }}</b><span>待报送</span></div>
        </div>
        <div class="ov-cell">
          <i class="dot" style="background: #2fa37f"></i>
          <div><b class="mono-num">{{ overview.done }}</b><span>已完成</span></div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="operations-head">
        <div>
          <h3 class="card-title">风险优先运营队列</h3>
          <p class="section-hint">按确定性业务事实和分阶段 SLA 排序；逾期优先，其次按风险评分与截止时间。</p>
        </div>
        <div class="operations-filters">
          <el-select v-model="operationsPriority" clearable placeholder="全部优先级" @change="loadOperations">
            <el-option v-for="(label, value) in priorityText" :key="value" :label="label" :value="value" />
          </el-select>
          <el-select v-model="operationsPhase" clearable placeholder="全部阶段" @change="loadOperations">
            <el-option v-for="(label, value) in phaseText" :key="value" :label="label" :value="value" />
          </el-select>
          <el-checkbox v-model="operationsOverdueOnly" @change="loadOperations">只看逾期</el-checkbox>
          <el-button :loading="operationsLoading" @click="loadOperations"><el-icon><Refresh /></el-icon></el-button>
        </div>
      </div>
      <el-alert v-if="operationsError" type="error" :closable="false" title="案件运营队列加载失败，请刷新重试" />
      <el-empty v-else-if="!operations.length && !operationsLoading" description="当前角色暂无运营待办" :image-size="52" />
      <el-table v-else :data="operations" v-loading="operationsLoading" stripe>
        <el-table-column label="优先级" width="100">
          <template #default="{ row }">
            <el-tag :type="priorityTag(row.priority)" effect="dark">{{ priorityText[row.priority as CasePriority] }}</el-tag>
            <span class="priority-score">{{ row.priorityScore }}</span>
          </template>
        </el-table-column>
        <el-table-column label="案件 / 客户" width="150">
          <template #default="{ row }"><span class="mono-num">#{{ row.caseId }}</span><br />{{ row.customerName }}（{{ row.customerId }}）</template>
        </el-table-column>
        <el-table-column label="当前阶段" width="140">
          <template #default="{ row }">{{ phaseText[row.phase as OperationPhase] }}</template>
        </el-table-column>
        <el-table-column label="责任队列" width="165">
          <template #default="{ row }">{{ row.assignedTo || row.assignedUnit }}<br /><span class="muted">{{ row.responsibleRole }}</span></template>
        </el-table-column>
        <el-table-column label="优先原因" min-width="260">
          <template #default="{ row }">{{ row.priorityReasons.join('；') }}</template>
        </el-table-column>
        <el-table-column label="阶段时限" width="195">
          <template #default="{ row }">
            <span class="mono-num" :class="{ 'ops-overdue': row.overdue }">{{ fmtDateTime(row.dueAt) }}</span><br />
            <strong :class="row.overdue ? 'ops-overdue' : 'ops-remaining'">{{ remainingText(row as CaseOperationsView) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button size="small" type="primary" @click="emit('open-case', row.caseId)">办理</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="['ANALYST', 'ADMIN'].includes(currentUser?.role ?? '')" class="card">
      <h3 class="card-title">原始预警分诊</h3>
      <p class="section-hint">预警先进入分诊队列，再决定新建案件、归并到同客户待处理案件，或按重复项关闭。</p>
      <div class="alert-create-grid">
        <el-input v-model="alertExternalId" maxlength="64" placeholder="外部预警编号" />
        <el-select v-model="selectedCustomer" placeholder="客户">
          <el-option v-for="c in customers" :key="c.id" :label="`${c.name}（${c.id}）`" :value="c.id" />
        </el-select>
        <el-input v-model="alertRuleCode" maxlength="64" placeholder="监测规则码" />
        <el-select v-model="alertScenarioCode" placeholder="调查场景">
          <el-option v-for="scenario in scenarioOptions" :key="scenario.value" :label="scenario.label" :value="scenario.value" />
        </el-select>
        <el-input v-model="alertHitReason" maxlength="500" placeholder="说明命中的客户、交易或行为特征" class="alert-reason" />
        <el-button type="primary" :loading="alertSubmitting" @click="createInboxAlert">录入预警</el-button>
      </div>
      <el-alert v-if="alertInboxError" type="error" :closable="false" title="预警分诊队列加载失败，请刷新重试" />
      <el-empty v-else-if="!alertInbox.length" description="暂无待分诊预警" :image-size="52" />
      <el-table v-else :data="alertInbox" stripe>
        <el-table-column prop="externalAlertId" label="预警编号" width="150" />
        <el-table-column label="客户" width="130">
          <template #default="{ row }">{{ row.customerId }}</template>
        </el-table-column>
        <el-table-column label="场景" width="170">
          <template #default="{ row }">{{ scenarioName(row.scenarioCode) }}</template>
        </el-table-column>
        <el-table-column prop="hitReason" label="命中说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="发生时间" width="168">
          <template #default="{ row }"><span class="mono-num time">{{ fmtDateTime(row.occurredAt) }}</span></template>
        </el-table-column>
        <el-table-column label="分诊" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" :loading="triagingAlertId === row.id" @click="createAlertCase(row as AmlAlert, false)">新建案件</el-button>
            <el-button size="small" type="primary" plain :loading="triagingAlertId === row.id" @click="createAlertCase(row as AmlAlert, true)">建案并调查</el-button>
            <el-button size="small" @click="mergeAlert(row as AmlAlert)">归并</el-button>
            <el-button size="small" type="danger" plain @click="closeAsDuplicate(row as AmlAlert)">重复</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="['ANALYST', 'ADMIN'].includes(currentUser?.role ?? '')" class="card">
      <h3 class="card-title">{{ currentUser?.role === 'ADMIN' ? '全部补充尽调待办' : '我的补充尽调待办' }}</h3>
      <el-alert v-if="eddLoadError" type="error" :closable="false" title="补充尽调待办加载失败，请刷新重试" />
      <el-empty v-else-if="!eddTasks.length" description="暂无待补充任务" :image-size="52" />
      <el-table v-else :data="eddTasks" stripe>
        <el-table-column label="案件" width="90">
          <template #default="{ row }"><span class="mono-num">#{{ row.caseId }}</span></template>
        </el-table-column>
        <el-table-column label="承办人" width="120" prop="assignedTo" />
        <el-table-column label="承办部门" width="150" prop="assignedUnit" />
        <el-table-column label="所需材料" min-width="220">
          <template #default="{ row }">{{ row.requiredItems.map((item: string) => eddRequiredItemText[item] ?? item).join('、') }}</template>
        </el-table-column>
        <el-table-column label="截止时间" width="190">
          <template #default="{ row }">
            <span class="mono-num" :style="row.overdue ? { color: '#c43d4b', fontWeight: '700' } : {}">
              {{ fmtDateTime(row.dueAt) }}{{ row.overdue ? '（已逾期）' : '' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button size="small" type="primary" @click="emit('open-case', row.caseId)">办理</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建预警工单 -->
    <div class="card">
      <h3 class="card-title">新建预警工单</h3>
      <div class="create-bar">
        <el-select v-model="selectedCustomer" placeholder="选择客户" style="width: 220px">
          <el-option v-for="c in customers" :key="c.id" :label="`${c.name}（${c.id}）`" :value="c.id" />
        </el-select>
        <el-input v-model="alertRule" placeholder="预警规则描述" style="flex: 1" />
        <el-button :loading="loading" @click="handleCreate(false)">
          <el-icon><Plus /></el-icon>
          <span>创建案件</span>
        </el-button>
        <el-button type="primary" :loading="loading" @click="handleCreate(true)">
          <el-icon><Plus /></el-icon>
          <span>创建并尽调</span>
        </el-button>
      </div>
    </div>

    <!-- 工单列表 -->
    <div class="card">
      <h3 class="card-title">预警工单列表</h3>
      <el-table :data="cases" v-loading="listLoading" stripe style="width: 100%">
        <el-table-column label="工单号" width="88">
          <template #default="{ row }">
            <span class="mono-num case-id">#{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="客户" width="130">
          <template #default="{ row }">{{ row.customerName }}<span class="cid">{{ row.customerId }}</span></template>
        </el-table-column>
        <el-table-column prop="alertRule" label="预警规则" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <span class="st" :class="statusMeta[row.status]?.cls">
              <i class="dot" :style="{ background: statusMeta[row.status]?.dot }"></i>
              {{ statusMeta[row.status]?.text ?? row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="风险评级" width="96">
          <template #default="{ row }">
            <span v-if="row.riskLevel" class="rk" :class="riskMeta[row.riskLevel]?.cls">{{ row.riskLevel }}</span>
            <span v-else class="rk-none">-</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="92">
          <template #default="{ row }">
            <span v-if="srcTag(row as CaseItem)" class="src">{{ srcTag(row as CaseItem) }}</span>
            <span v-else class="rk-none">-</span>
          </template>
        </el-table-column>
        <el-table-column label="人工处置" width="104">
          <template #default="{ row }">
            <span v-if="row.reviewDisposition" class="src">{{ dispositionText[row.reviewDisposition] ?? row.reviewDisposition }}</span>
            <span v-else class="rk-none">-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="168">
          <template #default="{ row }">
            <span class="mono-num time">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" @click="emit('open-case', row.id)">
                <el-icon><Search /></el-icon>
                <span>查看</span>
              </el-button>
              <el-button
                v-if="row.status === 'PENDING' || row.status === 'FAILED'"
                size="small"
                type="primary"
                :loading="processingId === row.id"
                @click="handleProcess(row as CaseItem)"
              >
                <el-icon v-if="processingId !== row.id"><Refresh /></el-icon>
                <span>{{ processingId === row.id ? (row.status === 'FAILED' ? '重试中' : '处理中') : (row.status === 'FAILED' ? '重试' : '开始调查') }}</span>
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty">
            <span class="empty-rule">暂无工单</span>
            <p>在左侧选择客户并创建预警工单开始尽调</p>
          </div>
        </template>
      </el-table>
      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          @current-change="onPageChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 0;
}

/* 态势概览 */
.overview {
  display: flex;
  align-items: stretch;
  gap: 0;
}

.ov-total {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-right: 28px;
  border-right: 1px solid var(--line);
  min-width: 150px;
}

.ov-label {
  font-size: 12px;
  color: var(--text-dim);
  letter-spacing: 0.05em;
}

.ov-total b {
  font-size: 38px;
  font-weight: 650;
  color: var(--text);
  line-height: 1.1;
  margin: 4px 0;
}

.ov-sub {
  font-size: 11px;
  color: var(--text-faint);
}

.ov-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0;
}

.ov-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  background: transparent;
  border-left: 1px solid var(--line);
  border-radius: 0;
}

.ov-cell .dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ov-cell .dot.pulse {
  animation: none;
}

.ov-cell div {
  display: flex;
  flex-direction: column;
}

.ov-cell b {
  font-size: 22px;
  font-weight: 650;
  color: var(--text);
}

.ov-cell span {
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.04em;
}

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(224, 162, 58, 0.35); }
  50% { box-shadow: 0 0 0 6px rgba(224, 162, 58, 0); }
}

/* 新建 */
.create-bar {
  display: flex;
  gap: 12px;
}

.section-hint { margin: -4px 0 14px; color: var(--text-faint); font-size: 12px; }
.operations-head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
.operations-filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.operations-filters .el-select { width: 138px; }
.priority-score { display: block; margin-top: 4px; color: var(--text-faint); font: 11px var(--font-mono); }
.ops-overdue { color: var(--risk-high); font-size: 12px; }
.ops-remaining { color: var(--risk-low); font-size: 12px; }
.alert-create-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1.2fr 1.2fr;
  gap: 10px;
  margin-bottom: 16px;
}
.alert-reason { grid-column: 1 / 4; }

/* 表格 */
.case-id {
  color: var(--text);
  font-weight: 600;
}

.cid {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-faint);
  margin-left: 6px;
}

.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 550;
  padding: 3px 10px;
  border-radius: 6px;
  border: 1px solid var(--line-faint);
}

.st .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.st-running {
  color: #e0a23a;
  border-color: rgba(224, 162, 58, 0.3);
  background: rgba(224, 162, 58, 0.08);
}
.st-hold {
  color: #c43d4b;
  border-color: rgba(196, 61, 75, 0.3);
  background: rgba(196, 61, 75, 0.08);
}
.st-done {
  color: #2fa37f;
  border-color: rgba(47, 163, 127, 0.3);
  background: rgba(47, 163, 127, 0.08);
}
.st-pending,
.st-failed {
  color: var(--text-dim);
}

.rk {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 6px;
}
.rk-high {
  color: #c43d4b;
  background: rgba(196, 61, 75, 0.12);
  border: 1px solid rgba(196, 61, 75, 0.32);
}
.rk-mid {
  color: #e0a23a;
  background: rgba(224, 162, 58, 0.12);
  border: 1px solid rgba(224, 162, 58, 0.32);
}
.rk-low {
  color: #2fa37f;
  background: rgba(47, 163, 127, 0.12);
  border: 1px solid rgba(47, 163, 127, 0.32);
}
.rk-none {
  color: var(--text-faint);
}

.src {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-dim);
  background: #f8fafc;
  border: 1px solid var(--line);
  padding: 2px 8px;
  border-radius: 6px;
}

.time {
  font-size: 12px;
  color: var(--text-dim);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.table-empty {
  padding: 26px 0;
  text-align: center;
}
.empty-rule {
  display: inline-block;
  font-size: 13px;
  color: var(--text-dim);
  border-bottom: 1px solid var(--line);
  padding-bottom: 4px;
}
.table-empty p {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-faint);
}

@media (max-width: 860px) {
  .operations-head { flex-direction: column; }
  .overview {
    flex-direction: column;
    gap: 16px;
  }
  .ov-total {
    border-right: none;
    border-bottom: 1px solid var(--line);
    padding: 0 0 14px;
  }
  .ov-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .create-bar {
    flex-direction: column;
  }
  .alert-create-grid { grid-template-columns: 1fr; }
  .alert-reason { grid-column: auto; }
}
</style>
