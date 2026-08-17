<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  getAgentEvalStatus,
  getAgentEvalDatasetSummary,
  runAgentDevEval,
} from '../api/client'
import { CaretTop, DataAnalysis, Warning } from '@element-plus/icons-vue'

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
  if (!v || v.value == null) return '-'
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
  <div class="eval">
    <el-alert
      v-if="status && !status.ready"
      type="warning"
      :title="status.message || '当前未配置真实模型，评测将拒绝运行（不会伪造质量指标）'"
      :closable="false"
      class="ready-alert"
    >
      <template #icon><el-icon><Warning /></el-icon></template>
    </el-alert>

    <div class="grid">
      <div class="card">
        <h3 class="card-title">Agent 评测数据集</h3>
        <template v-if="dataset">
          <div class="kv"><span>数据集</span><b class="mono-num">{{ dataset.datasetId }} <em>v{{ dataset.version }}</em></b></div>
          <div class="kv"><span>来源 / 标注</span><b>{{ dataset.sourceType }} · {{ dataset.annotationMethod }}</b></div>
          <div class="kv"><span>复核状态</span><el-tag size="small" type="warning" effect="dark">{{ dataset.reviewStatus }}</el-tag></div>
          <div class="kv"><span>案例总数</span><b class="mono-num">{{ dataset.totalCases }}</b></div>
          <div class="kv"><span>分片分布</span><b class="mono-num">{{ JSON.stringify(dataset.splitCounts) }}</b></div>
          <div class="kv"><span>风险等级分布</span><b class="mono-num">{{ JSON.stringify(dataset.riskLevelCounts) }}</b></div>
          <div class="kv hash"><span>数据集哈希（冻结审计）</span><code>{{ dataset.datasetHash }}</code></div>
        </template>
        <p v-else class="load-txt">加载中…</p>
      </div>

      <div class="card">
        <h3 class="card-title">v2 → v5 DEV 迭代对比</h3>
        <div class="baseline-list">
          <div v-for="row in baseline" :key="row.metric" class="bl-row">
            <span class="bl-metric">{{ row.metric }}</span>
            <span class="bl-v2 mono-num">{{ row.v2 }}</span>
            <el-icon class="bl-arrow"><CaretTop /></el-icon>
            <b class="bl-v5 mono-num">{{ row.v5 }}</b>
          </div>
        </div>
        <p class="hint">v5 为调优集结果（可能过拟合），需以冻结的隐藏 TEST 分片验证泛化。</p>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">运行真实模型评测</h3>
      <div class="run-bar">
        <el-button type="primary" :loading="loading" @click="runDev">
          <el-icon v-if="!loading"><DataAnalysis /></el-icon>
          <span>运行 DEV 分片</span>
        </el-button>
      </div>
      <p class="hint">需配置真实模型 Key（如 DEEPSEEK_API_KEY）；Mock/fallback 会被拒绝并返回 INVALID_MODEL_FALLBACK。隐藏 TEST 分片仅通过 CLI/集成测试一次性运行（RUN_HIDDEN_AGENT_EVAL=true），不在页面暴露。</p>
    </div>

    <div v-if="result" class="card">
      <h3 class="card-title">
        评测结果（{{ result.split }} · {{ result.runStatus }}）
      </h3>
      <div class="metrics">
        <div class="metric"><span>严格通过率</span><b class="mono-num">{{ rate(result.strictPassRate) }}</b></div>
        <div class="metric"><span>任务通过率</span><b class="mono-num">{{ rate(result.taskPassRate) }}</b></div>
        <div class="metric"><span>原始风险准确率</span><b class="mono-num">{{ rate(result.rawRisk?.exactAccuracy) }}</b></div>
        <div class="metric"><span>最终风险准确率</span><b class="mono-num">{{ rate(result.finalRisk?.exactAccuracy) }}</b></div>
        <div class="metric"><span>必需工具召回</span><b class="mono-num">{{ rate(result.tools?.requiredToolRecall) }}</b></div>
        <div class="metric"><span>evidenceId 召回</span><b class="mono-num">{{ rate(result.citations?.evidenceIdRecall) }}</b></div>
        <div class="metric"><span>Token（输入/输出）</span><b class="mono-num">{{ result.tokens?.inputTokens }} / {{ result.tokens?.outputTokens }}</b></div>
        <div class="metric"><span>延迟 P50/P95</span><b class="mono-num">{{ result.latency?.p50Ms }}ms / {{ result.latency?.p95Ms }}ms</b></div>
      </div>
      <p class="hint">promptVersion: <code>{{ result.promptVersion }}</code> · 模型: <code>{{ result.runtime?.configuredModel }}</code> · 评分: <code>{{ result.scored }}/{{ result.attempted }}</code></p>
    </div>
  </div>
</template>

<style scoped>
.eval {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.ready-alert {
  --el-alert-bg-color: rgba(224, 162, 58, 0.1);
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}

.kv {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line-faint);
  font-size: 13px;
}
.kv span { color: var(--text-dim); }
.kv b { color: var(--text); font-weight: 550; }
.kv b em { font-style: normal; color: var(--gold); }
.kv.hash { flex-direction: column; align-items: flex-start; gap: 4px; }
.kv.hash code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--gold);
  word-break: break-all;
  background: rgba(11, 18, 32, 0.5);
  border: 1px solid var(--line-faint);
  padding: 4px 8px;
  border-radius: 6px;
}

.load-txt { color: var(--text-faint); font-size: 13px; }

.baseline-list {
  display: flex;
  flex-direction: column;
}
.bl-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px solid var(--line-faint);
  font-size: 13px;
}
.bl-metric { flex: 1; color: var(--text-dim); }
.bl-v2 { color: var(--text-faint); min-width: 52px; text-align: right; }
.bl-arrow { color: var(--gold); font-size: 14px; }
.bl-v5 { color: var(--risk-low); min-width: 52px; font-weight: 600; }

.run-bar { display: flex; }

.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.metric {
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(11, 18, 32, 0.42);
  border: 1px solid var(--line-faint);
}
.metric span {
  display: block;
  font-size: 12px;
  color: var(--text-faint);
  margin-bottom: 6px;
}
.metric b {
  font-size: 17px;
  color: var(--text);
  font-weight: 650;
}

.eval .hint code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--gold);
  background: rgba(11, 18, 32, 0.5);
  padding: 2px 6px;
  border-radius: 6px;
}

@media (max-width: 900px) {
  .grid { grid-template-columns: 1fr; }
  .metrics { grid-template-columns: repeat(2, 1fr); }
}
</style>
