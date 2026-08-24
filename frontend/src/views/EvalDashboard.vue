<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  getAgentEvalStatus,
  getAgentEvalDatasetSummary,
  runAgentDevEval,
  type AgentEvalDatasetSummary,
  type AgentEvalResult,
  type EvalRate,
} from '../api/client'
import { CaretTop, DataAnalysis, Warning } from '@element-plus/icons-vue'
import { formatEvalRate } from '../utils/eval'

const status = ref<Awaited<ReturnType<typeof getAgentEvalStatus>> | null>(null)
const dataset = ref<AgentEvalDatasetSummary | null>(null)
const loading = ref(false)
const dataLoading = ref(true)
const result = ref<AgentEvalResult | null>(null)

onMounted(async () => {
  try {
    status.value = await getAgentEvalStatus()
    dataset.value = await getAgentEvalDatasetSummary()
  } catch {
    ElMessage.error('加载评测状态失败')
  } finally {
    dataLoading.value = false
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

const rate = (v: EvalRate | null | undefined): string => formatEvalRate(v)

// v2/v9 历史对比（冻结 DEV，v9 为最近一次真实 DeepSeek 全量运行）
const baseline = [
  { metric: '原始风险准确率', v2: '44.4%', v5: '88.9%' },
  { metric: '原始高风险召回率', v2: '40%', v5: '100%' },
  { metric: 'Guardrails 后风险准确率', v2: '77.8%', v5: '100%' },
  { metric: '必需工具召回率', v2: '94.4%', v5: '100%' },
  { metric: '法规 evidenceId 召回率', v2: '77.8%', v5: '100%' },
  { metric: '无效输出', v2: '2/9', v5: '0/9' },
  { metric: '端到端任务通过率', v2: '未统计', v5: '100%' },
]
</script>

<template>
  <div class="eval">
    <header class="page-intro">
      <h2>评测</h2>
      <p>查看冻结数据集、质量基线和真实模型运行结果。</p>
    </header>
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
      <div class="card" v-loading="dataLoading">
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
        <h3 class="card-title">v2 → v9 DEV 迭代对比</h3>
        <div class="baseline-list">
          <div v-for="row in baseline" :key="row.metric" class="bl-row">
            <span class="bl-metric">{{ row.metric }}</span>
            <span class="bl-v2 mono-num">{{ row.v2 }}</span>
            <el-icon class="bl-arrow"><CaretTop /></el-icon>
            <b class="bl-v5 mono-num">{{ row.v5 }}</b>
          </div>
        </div>
        <p class="hint">v9 为公开 DEV 调优结果，仍需以冻结的隐藏 TEST 分片验证泛化。</p>
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
        <div class="metric"><span>平均 Token 预算</span><b :class="{ pass: result.efficiency?.tokenPass === true, fail: result.efficiency?.tokenPass === false }">{{ result.efficiency?.observedAverageTokensPerCase ?? '-' }} / {{ result.efficiency?.averageTokensPerCaseBudget ?? '-' }}</b></div>
        <div class="metric"><span>P95 延迟预算</span><b :class="{ pass: result.efficiency?.latencyPass === true, fail: result.efficiency?.latencyPass === false }">{{ result.efficiency?.observedP95LatencyMs ?? '-' }} / {{ result.efficiency?.p95LatencyBudgetMs ?? '-' }} ms</b></div>
      </div>
      <p class="hint">promptVersion: <code>{{ result.promptVersion }}</code> · 模型: <code>{{ result.runtime?.configuredModel }}</code> · 评分: <code>{{ result.scored }}/{{ result.attempted }}</code></p>
    </div>
  </div>
</template>

<style scoped>
.eval {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.ready-alert {
  --el-alert-bg-color: rgba(224, 162, 58, 0.1);
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 28px;
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
  background: #f8fafc;
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
  gap: 0;
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow: hidden;
}
.metric {
  padding: 12px 14px;
  border-radius: 0;
  background: #ffffff;
  border: 0;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
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
.metric b.pass { color: var(--risk-low); }
.metric b.fail { color: var(--risk-high); }

.grid .card { border-top: 0; padding-top: 0; }

.eval .hint code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--gold);
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 6px;
}

@media (max-width: 900px) {
  .grid { grid-template-columns: 1fr; }
  .metrics { grid-template-columns: repeat(2, 1fr); }
}
</style>
