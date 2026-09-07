<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
  submitReview, getExplanationReviewBasis,
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

const emit = defineEmits<{ (e: 'open-case', id: number): void }>()

const pending = ref<CaseItem[]>([])
const stats = ref({ reviewedCount: 0, agreementRate: 0, confirmedSuspiciousCount: 0, falsePositiveCount: 0, eddRequestedCount: 0 })
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
const assignees = ref<Array<{ username: string; role: string }>>([])
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

const reasonsByDecision: Record<string, Array<{ value: string; label: string }>> = {
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

function defaultDueAt(): string {
  const date = new Date()
  date.setDate(date.getDate() + 5)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
}

onMounted(async () => {
  await refresh()
})

async function refresh() {
  try {
    const [p, s, users, reports, operations] = await Promise.all([
      listPendingReviews(), reviewStats(), listEnhancedDueDiligenceAssignees(),
      listPendingSuspiciousReports(), listCaseOperations(),
    ])
    operationsByCase.value = new Map(operations.map(item => [item.caseId, item]))
    const order = new Map(operations.map((item, index) => [item.caseId, index]))
    pending.value = [...p].sort((left, right) =>
      (order.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER))
    stats.value = s
    assignees.value = users
    pendingReports.value = [...reports].sort((left, right) =>
      (order.get(left.caseId) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.caseId) ?? Number.MAX_SAFE_INTEGER))
    if (!assignedTo.value && users.length) assignedTo.value = users[0].username
  } catch {
    ElMessage.error('加载复核数据失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function openReview(row: CaseItem) {
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
      listEnhancedDueDiligence(row.id), getCaseInvestigation(row.id),
  ])
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
  eddLoading.value = false
}

const priorityLabels: Record<CasePriority, string> = {
  CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', NORMAL: '常规',
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
  if (!reviewing.value) return
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
  if (decision.value === 'REQUEST_ENHANCED_DUE_DILIGENCE'
      && (!requiredItems.value.length || !dueAt.value || !assignedTo.value || assignedUnit.value.trim().length < 2)) {
    ElMessage.warning('请选择补充材料、承办人、承办部门并设置截止时间')
    return
  }
  submitting.value = true
  try {
    // v2 解释核验案件：最终复核必须携带服务端取号的依据令牌；决策表不通过时阻断并显示阻断项
    let reviewBasisToken: string | undefined
    if ((reviewing.value as { investigationContractVersion?: number }).investigationContractVersion === 2
        && decision.value !== 'REQUEST_ENHANCED_DUE_DILIGENCE') {
      const basis = await getExplanationReviewBasis(reviewing.value.id)
      reviewBasisToken = basis.reviewBasisToken
      const blockers = decision.value === 'EXCLUDE_FALSE_POSITIVE'
        ? basis.excludeBlockers : basis.confirmBlockers
      if ((decision.value === 'EXCLUDE_FALSE_POSITIVE' && !basis.canExclude)
          || (decision.value === 'CONFIRM_SUSPICIOUS' && !basis.canConfirm)) {
        ElMessage.warning(`决策表未通过：${blockers.join('；') || '请先补齐单元提交与义务接续'}`)
        submitting.value = false
        return
      }
    }
    await submitReview(reviewing.value.id, {
      reviewerRiskLevel: reviewerRiskLevel.value,
      decision: decision.value,
      reasonCode: reasonCode.value,
      comment: comment.value,
      expectedReviewRevision: reviewing.value.reviewRevision ?? 0,
      reviewBasisToken,
      ...(decision.value === 'REQUEST_ENHANCED_DUE_DILIGENCE'
        ? {
            requiredItems: requiredItems.value,
            dueAt: dueAt.value,
            assignedTo: assignedTo.value,
            assignedUnit: assignedUnit.value.trim(),
          }
        : {}),
    })
    ElMessage.success('复核已提交')
    dialogOpen.value = false
    await refresh()
  } catch (e) {
    // 并发冲突（409）：另一方已复核，提示用户刷新查看，避免误以为成功
    if ((e as any)?.response?.status === 409) {
      ElMessage.error('该工单已被其他人复核，状态已变化，请刷新查看最新信息')
    } else {
      ElMessage.error('提交失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}

async function cancelActiveEdd() {
  if (!reviewing.value || activeEdd.value?.status !== 'OPEN') return
  try {
    const { value } = await ElMessageBox.prompt('撤销后案件仍保持待复核，请说明原因。', '撤销补充尽调', {
      inputType: 'textarea',
      inputPlaceholder: '说明误发、需求变化或其他撤销原因（至少 10 个字符）',
      inputValidator: (text: string) => text?.trim().length >= 10 || '撤销原因至少 10 个字符',
    })
    cancellingEdd.value = true
    activeEdd.value = await cancelEnhancedDueDiligence(reviewing.value.id, activeEdd.value.id, {
      expectedRevision: activeEdd.value.revision,
      reason: value.trim(),
    })
    ElMessage.success('补充尽调任务已撤销，可重新处置案件')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message ?? '撤销失败，请刷新后重试')
  } finally {
    cancellingEdd.value = false
  }
}

async function markReportSubmitted(report: SuspiciousTransactionReport) {
  try {
    const { value } = await ElMessageBox.prompt(
      '请填写外部可疑交易报告系统返回的受理编号。', '登记报送完成', {
        inputPlaceholder: '例如 STR-2026-000123',
        inputValidator: (text: string) => /^[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}$/.test(text?.trim())
          || '请输入至少 3 位有效受理编号',
      },
    )
    submittingReportId.value = report.id
    await submitSuspiciousReport(report.caseId, report.revision, value.trim())
    ElMessage.success('已登记报送完成，案件正式结束')
    await refresh()
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message ?? '登记报送失败，请刷新后重试')
  } finally {
    submittingReportId.value = null
  }
}
</script>

<template>
  <div class="review">
    <header class="page-intro">
      <h2>人工复核</h2>
      <p>处理 Agent 升级工单，并记录可审计的最终决定。</p>
    </header>
    <!-- 决策闭环统计 -->
    <div class="stats">
      <div class="stat-main">
        <span class="stat-label">Agent 与人工一致率</span>
        <b class="mono-num">{{ stats.agreementRate }}%</b>
        <div class="agree-bar">
          <i :style="{ width: stats.agreementRate + '%' }"></i>
        </div>
      </div>
      <div class="stat-cells">
        <div class="stat-cell">
          <span>处置记录</span>
          <b class="mono-num">{{ stats.reviewedCount }}</b>
        </div>
        <div class="stat-cell">
          <span>确认可疑</span>
          <b class="mono-num" style="color: var(--risk-high)">{{ stats.confirmedSuspiciousCount }}</b>
        </div>
        <div class="stat-cell">
          <span>排除预警</span>
          <b class="mono-num" style="color: var(--risk-low)">{{ stats.falsePositiveCount }}</b>
        </div>
        <div class="stat-cell">
          <span>补充尽调</span>
          <b class="mono-num" style="color: var(--risk-mid)">{{ stats.eddRequestedCount }}</b>
        </div>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">可疑交易报告待办</h3>
      <el-empty v-if="!pendingReports.length" description="暂无待报送或待补正报告" :image-size="52" />
      <el-table v-else :data="pendingReports" stripe>
        <el-table-column label="案件" width="90">
          <template #default="{ row }"><span class="mono-num">#{{ row.caseId }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">{{ row.status === 'RETURNED_FOR_CORRECTION' ? '退回补正' : '待报送' }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="92">
          <template #default="{ row }">
            <el-tag :type="priorityType(operationsFor(row.caseId)?.priority)" effect="dark">
              {{ priorityLabels[operationsFor(row.caseId)?.priority ?? 'NORMAL'] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SLA 截止" min-width="185">
          <template #default="{ row }">
            <span :class="{ 'deadline-overdue': operationsFor(row.caseId)?.overdue }">{{ deadlineText(row.caseId) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reportReason" label="报告理由" min-width="300" show-overflow-tooltip />
        <el-table-column prop="returnReason" label="退回原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button size="small" @click="emit('open-case', row.caseId)">查看案件</el-button>
            <el-button size="small" type="primary" :loading="submittingReportId === row.id" @click="markReportSubmitted(row as SuspiciousTransactionReport)">登记报送</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 待复核队列 -->
    <div class="card">
      <h3 class="card-title">待复核队列（HOLD）</h3>
      <el-table :data="pending" v-loading="loading" stripe>
        <el-table-column label="工单号" width="88">
          <template #default="{ row }"><span class="mono-num case-id">#{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="客户" width="140">
          <template #default="{ row }">{{ row.customerName }}<span class="cid">{{ row.customerId }}</span></template>
        </el-table-column>
        <el-table-column prop="alertRule" label="预警规则" min-width="180" show-overflow-tooltip />
        <el-table-column label="业务优先级" width="110">
          <template #default="{ row }">
            <el-tag :type="priorityType(operationsFor(row.id)?.priority)" effect="dark">
              {{ priorityLabels[operationsFor(row.id)?.priority ?? 'NORMAL'] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="复核 SLA" min-width="185">
          <template #default="{ row }">
            <span :class="{ 'deadline-overdue': operationsFor(row.id)?.overdue }">{{ deadlineText(row.id) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="评级" width="96">
          <template #default="{ row }">
            <span v-if="row.riskLevel" class="rk" :class="riskMeta[row.riskLevel]?.cls">{{ row.riskLevel }}</span>
            <span v-else class="rk-none">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default>
            <span class="st-hold">转人工</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button size="small" @click="emit('open-case', row.id)">
                <el-icon><Search /></el-icon>
                <span>查看详情</span>
              </el-button>
              <el-button size="small" type="primary" @click="openReview(row as CaseItem)">
                <el-icon><Stamp /></el-icon>
                <span>复核</span>
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="pending.length === 0" description="暂无待复核工单">
        <template #image><div class="empty-mark">✓</div></template>
      </el-empty>
    </div>

    <el-dialog v-model="dialogOpen" title="提交复核决定" width="540px">
      <div v-if="reviewing">
        <div class="rv-head">
          <div>
            <span class="rv-id mono-num">#{{ reviewing.id }}</span>
            <span class="rv-name">{{ reviewing.customerName }}</span>
          </div>
          <span class="rv-agent">Agent 评级 · {{ reviewing.riskLevel }}</span>
        </div>
        <el-alert
          v-if="activeEdd?.status === 'OPEN'"
          type="warning"
          :closable="false"
          show-icon
          :title="`第 ${activeEdd.roundNo} 轮补充尽调待提交，承办人 ${activeEdd.assignedTo ?? '-'}，截止 ${fmtDateTime(activeEdd.dueAt)}${activeEdd.overdue ? '（已逾期）' : ''}`"
        >
          <template #default>
            <el-button size="small" type="danger" plain :loading="cancellingEdd" @click="cancelActiveEdd">撤销任务</el-button>
          </template>
        </el-alert>
        <el-alert
          v-else-if="activeEdd?.status === 'SUBMITTED'"
          type="success"
          :closable="false"
          show-icon
          :title="`第 ${activeEdd.roundNo} 轮材料已提交，可重新作出处置`"
        >
          <template #default>
            <p class="edd-summary">{{ activeEdd.responseSummary }}</p>
            <span class="mono-num">证据编号：{{ activeEdd.evidenceReferences.join('、') }}</span>
          </template>
        </el-alert>
        <el-alert
          v-if="decision !== 'REQUEST_ENHANCED_DUE_DILIGENCE'"
          :type="currentInvestigationBlockers.length ? 'warning' : 'success'"
          :closable="false"
          show-icon
          :title="currentInvestigationBlockers.length ? '调查链路尚未满足最终处置条件' : '调查链路已满足当前处置条件'"
        >
          <template #default>
            <p v-if="currentInvestigationBlockers.length" class="investigation-blockers">
              {{ currentInvestigationBlockers.join('；') }}
            </p>
            <el-button size="small" plain @click="emit('open-case', reviewing.id)">打开案件调查链</el-button>
          </template>
        </el-alert>
        <el-form label-width="90px">
          <el-form-item label="复核评级">
            <el-radio-group v-model="reviewerRiskLevel">
              <el-radio value="高风险">高风险</el-radio>
              <el-radio value="中风险">中风险</el-radio>
              <el-radio value="低风险">低风险</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="复核决定">
            <el-radio-group v-model="decision" :disabled="activeEdd?.status === 'OPEN'">
              <el-radio value="CONFIRM_SUSPICIOUS">确认可疑</el-radio>
              <el-radio value="EXCLUDE_FALSE_POSITIVE">排除预警</el-radio>
              <el-radio value="REQUEST_ENHANCED_DUE_DILIGENCE">补充尽调</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="处置原因">
            <el-select v-model="reasonCode" style="width: 100%" placeholder="选择结构化原因">
              <el-option v-for="reason in currentReasons" :key="reason.value" :label="reason.label" :value="reason.value" />
            </el-select>
          </el-form-item>
          <template v-if="decision === 'REQUEST_ENHANCED_DUE_DILIGENCE'">
            <el-form-item label="补充材料">
              <el-select v-model="requiredItems" multiple collapse-tags style="width: 100%" placeholder="选择 1 ~ 5 项">
                <el-option v-for="item in requiredItemOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="截止时间">
              <el-date-picker
                v-model="dueAt"
                type="datetime"
                value-format="YYYY-MM-DDTHH:mm:ss"
                placeholder="选择补充材料截止时间"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="承办人">
              <el-select v-model="assignedTo" style="width: 100%" placeholder="选择分析员">
                <el-option v-for="user in assignees" :key="user.username" :label="user.username" :value="user.username" />
              </el-select>
            </el-form-item>
            <el-form-item label="承办部门">
              <el-input v-model="assignedUnit" maxlength="64" placeholder="例如：反洗钱分析组" />
            </el-form-item>
          </template>
          <el-form-item label="分析记录">
            <el-input
              v-model="comment"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="记录核验的客户信息、交易特征、证据与判断理由（至少 10 个字符）"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting || eddLoading"
          :disabled="activeEdd?.status === 'OPEN' || currentInvestigationBlockers.length > 0"
          @click="doSubmit"
        >提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.review {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.stats {
  display: flex;
  gap: 20px;
  align-items: stretch;
  background: transparent;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  padding: 20px 0;
}

.stat-main {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-right: 24px;
  border-right: 1px solid var(--line);
  min-width: 200px;
}

.stat-label {
  font-size: 12px;
  color: var(--text-dim);
  letter-spacing: 0.05em;
}

.stat-main b {
  font-size: 40px;
  font-weight: 650;
  color: var(--text);
  line-height: 1.1;
  margin: 6px 0 12px;
}

.agree-bar {
  height: 4px;
  border-radius: 2px;
  background: #e2e8f0;
  overflow: hidden;
}
.agree-bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--risk-info);
  box-shadow: none;
}

.stat-cells {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 4px;
  padding: 12px;
  background: transparent;
  border-left: 1px solid var(--line);
  border-radius: 0;
}
.stat-cell span { font-size: 11px; color: var(--text-faint); letter-spacing: 0.04em; }
.stat-cell b { font-size: 22px; font-weight: 650; color: var(--text); }

.case-id { color: var(--text); font-weight: 600; }
.cid { font-family: var(--font-mono); font-size: 11px; color: var(--text-faint); margin-left: 6px; }

.rk { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 6px; }
.rk-high { color: #c43d4b; background: rgba(196, 61, 75, 0.12); border: 1px solid rgba(196, 61, 75, 0.32); }
.rk-mid { color: #e0a23a; background: rgba(224, 162, 58, 0.12); border: 1px solid rgba(224, 162, 58, 0.32); }
.rk-low { color: #2fa37f; background: rgba(47, 163, 127, 0.12); border: 1px solid rgba(47, 163, 127, 0.32); }
.rk-none { font-size: 12px; color: var(--text-faint); }

.st-hold {
  font-size: 12px;
  font-weight: 550;
  color: #c43d4b;
  background: rgba(196, 61, 75, 0.1);
  border: 1px solid rgba(196, 61, 75, 0.3);
  padding: 3px 10px;
  border-radius: 6px;
}

.deadline-overdue {
  color: var(--risk-high);
  font-weight: 650;
}

.empty-mark {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 26px;
  color: var(--risk-low);
  border: 2px solid rgba(47, 163, 127, 0.4);
  background: rgba(47, 163, 127, 0.08);
}

.rv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #f8fafc;
  border: 1px solid var(--line-faint);
  border-radius: 8px;
}
.rv-id { color: var(--gold); font-weight: 600; margin-right: 10px; }
.rv-name { color: var(--text); font-weight: 600; }
.rv-agent { font-size: 12px; color: var(--text-dim); }

@media (max-width: 860px) {
  .stats { flex-direction: column; }
  .stat-main { border-right: none; border-bottom: 1px solid var(--line); padding: 0 0 16px; }
  .stat-cells { grid-template-columns: repeat(2, 1fr); }
}
</style>
