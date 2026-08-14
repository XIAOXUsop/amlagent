<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  getAgentEvalStatus,
  getAgentEvalDatasetSummary,
  runAgentDevEval,
} from '../api/client'

const status = ref<Record<string, any> | null>(null)
const dataset = ref<Record<string, any> | null>(null)
const loading = ref(false)
const result = ref<Record<string, any> | null>(null)

onMounted(async () => {
  try {
    status.value = await getAgentEvalStatus()
    dataset.value = await getAgentEvalDatasetSummary()
  } catch {
    ElMessage.error('加载评测状态失败')
  }
})

async function runDev() {
  loading.value = true
  result.value = null
  try {
    result.value = await runAgentDevEval()
    ElMessage.success('DEV 评测已提交')
  } catch {
    ElMessage.error('DEV 评测失败（可能未配置真实模型 Key）')
  } finally {
    loading.value = false
  }
}

function rate(v: any): string {
  if (!v || v.value == null) return '—'
  return `${(v.value * 100).toFixed(1)}%`
}

// v2/v5 历史对比（冻结 DEV，来自二轮对比报告）
const baseline = [
  { metric: '原始风险准确率', v2: '44.4%', v5: '100%' },
  { metric: '原始高风险召回率', v2: '40%', v5: '100%' },
  { metric: 'Guardrails 后风险准确率', v2: '77.8%', v5: '100%' },
  { metric: '必需工具召回率', v2: '94.4%', v5: '100%' },
  { metric: '法规 evidenceId 召回率', v2: '77.8%', v5: '100%' },
  { metric: '无效输出', v2: '2/9', v5: '0/9' },
  { metric: '端到端任务通过率', v2: '未统计', v5: '66.7%' },
]
</script>

<template>
  <div class="eval-dashboard">
    <el-alert
      v-if="status && !status.ready"
      type="warning"
      :title="status.message || '当前未配置真实模型，评测将拒绝运行（不会伪造质量指标）'"
      :closable="false"
      style="margin-bottom: 16px"
    />

    <div class="grid">
      <div class="card">
        <h3 class="card-title">Agent 评测数据集</h3>
        <template v-if="dataset">
          <div class="kv"><span>数据集</span><b>{{ dataset.datasetId }} v{{ dataset.version }}</b></div>
          <div class="kv"><span>来源 / 标注</span><b>{{ dataset.sourceType }} · {{ dataset.annotationMethod }}</b></div>
          <div class="kv"><span>复核状态</span><el-tag size="small" type="warning">{{ dataset.reviewStatus }}</el-tag></div>
          <div class="kv"><span>案例总数</span><b>{{ dataset.totalCases }}</b></div>
          <div class="kv"><span>分片分布</span><b>{{ JSON.stringify(dataset.splitCounts) }}</b></div>
          <div class="kv"><span>风险等级分布</span><b>{{ JSON.stringify(dataset.riskLevelCounts) }}</b></div>
          <div class="kv hash"><span>数据集哈希（冻结审计）</span><code>{{ dataset.datasetHash }}</code></div>
        </template>
        <p v-else style="color: #909399">加载中…</p>
      </div>

      <div class="card">
        <h3 class="card-title">v2 → v5 DEV 迭代对比</h3>
        <el-table :data="baseline" size="small" border>
          <el-table-column prop="metric" label="指标" />
          <el-table-column prop="v2" label="v2 基线" width="110" align="center" />
          <el-table-column prop="v5" label="v5" width="110" align="center">
            <template #default="{ row }"><b style="color: #67c23a">{{ row.v5 }}</b></template>
          </el-table-column>
        </el-table>
        <p class="hint">v5 为调优集结果（可能过拟合），需以冻结的隐藏 TEST 分片验证泛化。</p>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">运行真实模型评测</h3>
      <div style="display: flex; gap: 12px">
        <el-button type="primary" :loading="loading" @click="runDev">运行 DEV 分片</el-button>
      </div>
      <p class="hint">需配置真实模型 Key（如 DEEPSEEK_API_KEY）；Mock/fallback 会被拒绝并返回 INVALID_MODEL_FALLBACK。隐藏 TEST 分片仅通过 CLI/集成测试一次性运行（RUN_HIDDEN_AGENT_EVAL=true），不在页面暴露。</p>
    </div>

    <div v-if="result" class="card">
      <h3 class="card-title">
        评测结果（{{ result.split }} · {{ result.runStatus }}）
      </h3>
      <div class="metrics">
        <div class="metric"><span>严格通过率</span><b>{{ rate(result.strictPassRate) }}</b></div>
        <div class="metric"><span>任务通过率</span><b>{{ rate(result.taskPassRate) }}</b></div>
        <div class="metric"><span>原始风险准确率</span><b>{{ rate(result.rawRisk?.exactAccuracy) }}</b></div>
        <div class="metric"><span>最终风险准确率</span><b>{{ rate(result.finalRisk?.exactAccuracy) }}</b></div>
        <div class="metric"><span>必需工具召回</span><b>{{ rate(result.tools?.requiredToolRecall) }}</b></div>
        <div class="metric"><span>evidenceId 召回</span><b>{{ rate(result.citations?.evidenceIdRecall) }}</b></div>
        <div class="metric"><span>Token（输入/输出）</span><b>{{ result.tokens?.inputTokens }} / {{ result.tokens?.outputTokens }}</b></div>
        <div class="metric"><span>延迟 P50/P95</span><b>{{ result.latency?.p50Ms }}ms / {{ result.latency?.p95Ms }}ms</b></div>
      </div>
      <p class="hint">promptVersion: {{ result.promptVersion }} · 模型: {{ result.runtime?.configuredModel }} · 评分: {{ result.scored }}/{{ result.attempted }}</p>
    </div>
  </div>
</template>

<style scoped>
.eval-dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.card {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}
.card-title {
  margin: 0 0 12px;
  font-size: 15px;
  color: #303133;
}
.kv {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
}
.kv span {
  color: #909399;
}
.kv.hash {
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
.kv.hash code {
  font-size: 11px;
  color: #409eff;
  word-break: break-all;
}
.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.metric {
  padding: 10px;
  border-radius: 6px;
  background: #f7f9fc;
}
.metric span {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.metric b {
  font-size: 16px;
  color: #303133;
}
@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
