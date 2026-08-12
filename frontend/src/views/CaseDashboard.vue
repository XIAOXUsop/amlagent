<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createCase, listCases, listCustomers, processCase, type CaseItem, type Customer } from '../api/client'

const emit = defineEmits<{ (e: 'open-case', id: number): void }>()

const cases = ref<CaseItem[]>([])
const customers = ref<Customer[]>([])
const selectedCustomer = ref<string>('')
const alertRule = ref('大额频繁跨国转账、夜间集中交易')
const loading = ref(false)
const processingId = ref<number | null>(null)
const page = ref(0)
const total = ref(0)
const pageSize = 10

const statusMeta: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  PENDING: { text: '待处理', type: 'info' },
  RUNNING: { text: '执行中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  HOLD: { text: '转人工', type: 'danger' },
  FAILED: { text: '失败', type: 'danger' },
}

const riskMeta: Record<string, { text: string; type: 'success' | 'warning' | 'danger' }> = {
  低风险: { text: '低风险', type: 'success' },
  中风险: { text: '中风险', type: 'warning' },
  高风险: { text: '高风险', type: 'danger' },
}

onMounted(async () => {
  await refresh()
  customers.value = await listCustomers()
  if (customers.value.length > 0) {
    selectedCustomer.value = customers.value[0].id
  }
})

async function refresh() {
  const p = await listCases(page.value, pageSize)
  cases.value = p.content
  total.value = p.totalElements
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
  loading.value = true
  try {
    const c = await createCase(selectedCustomer.value, alertRule.value.trim())
    ElMessage.success(`工单 #${c.id} 创建成功`)
    await refresh()
    emit('open-case', c.id)
  } finally {
    loading.value = false
  }
}

async function handleProcess(row: CaseItem) {
  processingId.value = row.id
  try {
    await processCase(row.id)
    emit('open-case', row.id)
  } catch (e: unknown) {
    ElMessage.error('触发尽调失败')
  } finally {
    processingId.value = null
  }
}

function fmtTime(s: string): string {
  return s ? s.replace('T', ' ').slice(0, 19) : '-'
}
</script>

<template>
  <div>
    <div class="card">
      <h3 class="card-title">新建预警工单</h3>
      <div class="create-bar">
        <el-select v-model="selectedCustomer" placeholder="选择客户" style="width: 200px">
          <el-option v-for="c in customers" :key="c.id" :label="`${c.name}（${c.id}）`" :value="c.id" />
        </el-select>
        <el-input v-model="alertRule" placeholder="预警规则描述" style="flex: 1" />
        <el-button type="primary" :loading="loading" @click="handleCreate">创建并尽调</el-button>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">预警工单列表</h3>
      <el-table :data="cases" stripe style="width: 100%">
        <el-table-column prop="id" label="工单号" width="80" />
        <el-table-column prop="customerName" label="客户" width="110">
          <template #default="{ row }">{{ row.customerName }}（{{ row.customerId }}）</template>
        </el-table-column>
        <el-table-column prop="alertRule" label="预警规则" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.status]?.type ?? 'info'">{{ statusMeta[row.status]?.text ?? row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险评级" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.riskLevel" :type="riskMeta[row.riskLevel]?.type ?? 'info'">{{ row.riskLevel }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="emit('open-case', row.id)">查看</el-button>
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'FAILED'"
              size="small"
              type="primary"
              :loading="processingId === row.id"
              @click="handleProcess(row)"
            >
              处理
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="display: flex; justify-content: flex-end; margin-top: 12px">
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
.create-bar {
  display: flex;
  gap: 12px;
}
</style>
