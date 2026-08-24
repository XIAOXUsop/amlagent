<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createCase, fmtDateTime, listCases, listCaseStats, listCustomers, processCase, retryCase, type CaseItem, type CaseStats, type Customer } from '../api/client'
import { riskMeta, statusMeta } from '../constants/case'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'

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

// 态势概览：来自后端全量统计接口（跨分页），不再以当前页数据冒充全局数字
const overview = computed(() => {
  const s = stats.value
  return {
    total: s?.total ?? total.value,
    pending: s?.pending ?? 0,
    running: s?.running ?? 0,
    hold: s?.hold ?? 0,
    done: s?.done ?? 0,
  }
})

onMounted(async () => {
  await refresh()
  loadStats()
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

async function handleCreate() {
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
    const c = await createCase(selectedCustomer.value, alertRule.value.trim())
    ElMessage.success(`工单 #${c.id} 创建成功`)
    await refresh()
    loadStats()
    emit('open-case', c.id)
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
          <i class="dot" style="background: #2fa37f"></i>
          <div><b class="mono-num">{{ overview.done }}</b><span>已完成</span></div>
        </div>
      </div>
    </div>

    <!-- 新建预警工单 -->
    <div class="card">
      <h3 class="card-title">新建预警工单</h3>
      <div class="create-bar">
        <el-select v-model="selectedCustomer" placeholder="选择客户" style="width: 220px">
          <el-option v-for="c in customers" :key="c.id" :label="`${c.name}（${c.id}）`" :value="c.id" />
        </el-select>
        <el-input v-model="alertRule" placeholder="预警规则描述" style="flex: 1" />
        <el-button type="primary" :loading="loading" @click="handleCreate">
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
                <span>{{ processingId === row.id ? (row.status === 'FAILED' ? '重试中' : '处理中') : (row.status === 'FAILED' ? '重试' : '处理') }}</span>
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
}
</style>
