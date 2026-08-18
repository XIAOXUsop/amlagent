<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listPendingReviews, reviewStats, submitReview, type CaseItem } from '../api/client'
import { riskMeta } from '../constants/case'
import { Search, Stamp } from '@element-plus/icons-vue'

const emit = defineEmits<{ (e: 'open-case', id: number): void }>()

const pending = ref<CaseItem[]>([])
const stats = ref({ reviewedCount: 0, agreementRate: 0, approvedCount: 0, rejectedCount: 0, escalatedCount: 0 })
const loading = ref(true)

const reviewing = ref<CaseItem | null>(null)
const dialogOpen = ref(false)
const reviewerRiskLevel = ref('高风险')
const decision = ref('APPROVE')
const comment = ref('')
const submitting = ref(false)

onMounted(async () => {
  await refresh()
})

async function refresh() {
  try {
    const [p, s] = await Promise.all([listPendingReviews(), reviewStats()])
    pending.value = p
    stats.value = s
  } catch {
    ElMessage.error('加载复核数据失败，请稍后重试')
  } finally {
    loading.value = false
  }
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
</script>

<template>
  <div class="review">
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
          <span>已复核</span>
          <b class="mono-num">{{ stats.reviewedCount }}</b>
        </div>
        <div class="stat-cell">
          <span>批准</span>
          <b class="mono-num" style="color: var(--risk-low)">{{ stats.approvedCount }}</b>
        </div>
        <div class="stat-cell">
          <span>驳回</span>
          <b class="mono-num" style="color: var(--risk-high)">{{ stats.rejectedCount }}</b>
        </div>
        <div class="stat-cell">
          <span>升级</span>
          <b class="mono-num" style="color: var(--risk-mid)">{{ stats.escalatedCount }}</b>
        </div>
      </div>
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
            <el-button size="small" @click="emit('open-case', row.id)">
              <el-icon><Search /></el-icon>
              <span>查看详情</span>
            </el-button>
            <el-button size="small" type="primary" @click="openReview(row as CaseItem)">
              <el-icon><Stamp /></el-icon>
              <span>复核</span>
            </el-button>
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
.review {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.stats {
  display: flex;
  gap: 20px;
  align-items: stretch;
  background: linear-gradient(180deg, rgba(22, 33, 58, 0.92), rgba(17, 26, 44, 0.92));
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 20px;
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
  color: var(--gold);
  line-height: 1.1;
  margin: 6px 0 12px;
}

.agree-bar {
  height: 4px;
  border-radius: 2px;
  background: rgba(11, 18, 32, 0.6);
  overflow: hidden;
}
.agree-bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--gold), var(--gold-soft));
  box-shadow: 0 0 8px rgba(201, 169, 97, 0.5);
}

.stat-cells {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 4px;
  padding: 12px;
  background: rgba(11, 18, 32, 0.42);
  border: 1px solid var(--line-faint);
  border-radius: 8px;
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
  background: rgba(11, 18, 32, 0.5);
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
