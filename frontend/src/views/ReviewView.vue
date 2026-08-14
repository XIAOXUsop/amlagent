<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listPendingReviews, reviewStats, submitReview, type CaseItem } from '../api/client'

const emit = defineEmits<{ (e: 'open-case', id: number): void }>()

const pending = ref<CaseItem[]>([])
const stats = ref({ reviewedCount: 0, agreementRate: 0, approvedCount: 0, rejectedCount: 0, escalatedCount: 0 })

const reviewing = ref<CaseItem | null>(null)
const dialogOpen = ref(false)
const reviewerRiskLevel = ref('高风险')
const decision = ref('APPROVE')
const comment = ref('')
const submitting = ref(false)

const statusMeta: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  PENDING: { text: '待处理', type: 'info' },
  RUNNING: { text: '执行中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  HOLD: { text: '转人工', type: 'danger' },
  FAILED: { text: '失败', type: 'danger' },
}

onMounted(async () => {
  await refresh()
})

async function refresh() {
  pending.value = await listPendingReviews()
  stats.value = await reviewStats()
}

function openReview(row: CaseItem) {
  reviewing.value = row
  reviewerRiskLevel.value = row.riskLevel ?? '高风险'
  decision.value = 'APPROVE'
  comment.value = ''
  dialogOpen.value = true
}

async function doSubmit() {
  if (!reviewing.value) return
  submitting.value = true
  try {
    await submitReview(reviewing.value.id, {
      reviewerRiskLevel: reviewerRiskLevel.value,
      decision: decision.value,
      comment: comment.value,
      expectedReviewRevision: reviewing.value.reviewRevision ?? 0,
    })
    ElMessage.success('复核已提交')
    dialogOpen.value = false
    await refresh()
  } catch {
    ElMessage.error('提交失败')
  } finally {
    submitting.value = false
  }
}

</script>

<template>
  <div>
    <div class="card">
      <h3 class="card-title">人工复核 · 反馈闭环</h3>
      <div style="display: flex; gap: 24px">
        <div class="stat-item"><b>{{ stats.reviewedCount }}</b><span>已复核</span></div>
        <div class="stat-item"><b>{{ stats.agreementRate }}%</b><span>Agent 与人工一致率</span></div>
        <div class="stat-item"><b>{{ stats.approvedCount }}</b><span>批准</span></div>
        <div class="stat-item"><b>{{ stats.rejectedCount }}</b><span>驳回</span></div>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">待复核队列（HOLD）</h3>
      <el-table :data="pending" stripe>
        <el-table-column prop="id" label="工单号" width="80" />
        <el-table-column label="客户" width="140">
          <template #default="{ row }">{{ row.customerName }}（{{ row.customerId }}）</template>
        </el-table-column>
        <el-table-column prop="alertRule" label="预警规则" min-width="180" show-overflow-tooltip />
        <el-table-column label="评级" width="100">
          <template #default="{ row }">
            <el-tag :type="row.riskLevel === '高风险' ? 'danger' : row.riskLevel === '中风险' ? 'warning' : 'success'">
              {{ row.riskLevel ?? '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.status]?.type ?? 'info'">{{ statusMeta[row.status]?.text ?? row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="emit('open-case', row.id)">查看详情</el-button>
            <el-button size="small" type="primary" @click="openReview(row as CaseItem)">复核</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="pending.length === 0" description="暂无待复核工单" />
    </div>

    <el-dialog v-model="dialogOpen" title="提交复核决定" width="520px">
      <div v-if="reviewing">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="工单号">#{{ reviewing.id }}</el-descriptions-item>
          <el-descriptions-item label="客户">{{ reviewing.customerName }}</el-descriptions-item>
          <el-descriptions-item label="Agent 评级">{{ reviewing.riskLevel }}</el-descriptions-item>
          <el-descriptions-item label="执行版本">v{{ reviewing.executionVersion }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="90px">
          <el-form-item label="复核评级">
            <el-radio-group v-model="reviewerRiskLevel">
              <el-radio value="高风险">高风险</el-radio>
              <el-radio value="中风险">中风险</el-radio>
              <el-radio value="低风险">低风险</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="复核决定">
            <el-radio-group v-model="decision">
              <el-radio value="APPROVE">批准（APPROVE）</el-radio>
              <el-radio value="REJECT">驳回（REJECT）</el-radio>
              <el-radio value="ESCALATE">升级（ESCALATE）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="复核意见">
            <el-input v-model="comment" type="textarea" :rows="3" placeholder="填写复核意见" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.stat-item b {
  font-size: 22px;
  color: #1f4e79;
}
.stat-item span {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
</style>
