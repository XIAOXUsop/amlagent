<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  fmtDateTime,
  getCase,
  listLogs,
  parseReport,
  processCase,
  retryCase,
  subscribeCase,
  type CaseItem,
  type DueDiligenceReport,
  type WorkflowEvent,
} from '../api/client'
import { Back, RefreshRight, VideoPlay } from '@element-plus/icons-vue'

const props = defineProps<{ caseId: number }>()
const emit = defineEmits<{ (e: 'back'): void }>()

const caseItem = ref<CaseItem | null>(null)
const report = ref<DueDiligenceReport | null>(null)
const loadError = ref(false)
const detailLoading = ref(true)
const logs = ref<{ stage: string; content: string; at: string }[]>([])
const doneKeys = ref<Set<string>>(new Set())
const currentKey = ref('')
const logOpen = ref<string[]>(['log'])
const streamingText = ref('')
let unsubscribe: (() => void) | null = null

const workflowStages = [
  { key: 'PLANNING', label: '任务规划', desc: '解析预警工单，拆解子任务' },
  { key: 'COLLECTING', label: '数据采集', desc: '并行调用多源数据工具' },
  { key: 'REASONING', label: '风险推理', desc: '综合研判风险特征' },
  { key: 'GUARDRAIL', label: '规则护栏', desc: '制裁名单 / 评级一致性校验' },
  { key: 'REPORTING', label: '报告生成', desc: '结构化尽调初审报告' },
  { key: 'DONE', label: '完成', desc: '归档 / 转人工' },
]

const statusMeta: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' }> = {
  PENDING: { text: '待处理', type: 'info' },
  RUNNING: { text: '执行中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  HOLD: { text: '转人工', type: 'danger' },
  FAILED: { text: '失败', type: 'danger' },
}

const riskMeta: Record<string, { text: string; cls: string }> = {
  低风险: { text: '低风险', cls: 'rk-low' },
  中风险: { text: '中风险', cls: 'rk-mid' },
  高风险: { text: '高风险', cls: 'rk-high' },
}

function handleEvent(ev: WorkflowEvent) {
  logs.value.push({ stage: ev.stage, content: ev.content, at: new Date().toLocaleTimeString() })
  currentKey.value = ev.stage
  if (ev.stage === 'REPORTING') {
    doneKeys.value.add('REASONING')
  }
  if (ev.stage === 'DONE') {
    workflowStages.forEach((s) => doneKeys.value.add(s.key))
    doneKeys.value.add('REASONING')
  }
  doneKeys.value.add(ev.stage)
  if (['DONE', 'HOLD', 'FAILED'].includes(ev.stage)) {
    setTimeout(refresh, 800)
  }
}

async function refresh() {
  try {
    const c = await getCase(props.caseId)
    caseItem.value = c
    report.value = parseReport(c)
    loadError.value = false
  } catch {
    // 不静默吞错：标记错误，让页面展示"加载失败"态而非空白
    loadError.value = true
  } finally {
    detailLoading.value = false
  }
}

async function connect() {
  unsubscribe?.()
  logs.value = []
  doneKeys.value = new Set()
  currentKey.value = ''
  streamingText.value = ''
  await refresh()
  try {
    const history = await listLogs(props.caseId)
    history.forEach((l) => {
      if (['DONE', 'HOLD', 'FAILED'].includes(l.stage)) {
        workflowStages.forEach((s) => doneKeys.value.add(s.key))
      }
      doneKeys.value.add(l.stage)
      logs.value.push({ stage: l.stage, content: l.content, at: '' })
      currentKey.value = l.stage
    })
  } catch {
    // 历史日志拉取失败不影响订阅实时进度
    if (!logs.value.length) logs.value.push({ stage: 'PLANNING', content: '历史日志加载失败，正在订阅实时进度…', at: '' })
  }
  unsubscribe = subscribeCase(props.caseId, handleEvent, (token) => {
    streamingText.value += token
  })
}

onMounted(connect)

onUnmounted(() => {
  unsubscribe?.()
})

async function retryLoad() {
  loadError.value = false
  // detailLoading 可能已为 false，重置以显示加载态
  caseItem.value = null
  await connect()
}

async function handleRetry() {
  try {
    await retryCase(props.caseId)
    ElMessage.success('已重新入队，正在执行')
    await connect()
  } catch {
    ElMessage.error('人工重试失败，可能该工单不可重试，请刷新后重试')
  }
}

async function handleProcess() {
  try {
    await processCase(props.caseId)
    ElMessage.success('已触发尽调')
    await connect()
  } catch {
    ElMessage.error('触发尽调失败，可能该工单非待处理状态，请刷新后重试')
  }
}

const canRetry = computed(() => caseItem.value && ['HOLD', 'FAILED'].includes(caseItem.value.status))
const canProcess = computed(() => caseItem.value && ['PENDING', 'FAILED'].includes(caseItem.value.status))

function isDone(key: string): boolean {
  return doneKeys.value.has(key)
}

function isActive(key: string): boolean {
  return currentKey.value === key
}

function legalTitle(text: string): string {
  return text.split('\n')[0].replace(/^【|】$/g, '')
}

function legalBody(text: string): string {
  const idx = text.indexOf('】')
  return idx >= 0 ? text.slice(idx + 1) : text
}

function snapPrefix(id: string | null): string {
  return id && id.length > 12 ? id.slice(0, 12) + '…' : (id ?? '-')
}
</script>

<template>
  <div v-if="detailLoading" class="detail-loading">
    <el-icon class="is-loading load-icon"><RefreshRight /></el-icon>
    <span>正在加载工单…</span>
  </div>
  <div v-else-if="loadError" class="detail-error">
    <div class="err-mark">!</div>
    <p class="err-msg">工单加载失败，可能是工单不存在或网络异常。</p>
    <el-button type="primary" @click="retryLoad">重新加载</el-button>
  </div>
  <div v-else-if="caseItem" class="detail">
    <div class="detail-bar">
      <el-button size="small" @click="emit('back')">
        <el-icon><Back /></el-icon>
        <span>返回列表</span>
      </el-button>
      <div class="bar-right">
        <el-button v-if="canProcess" size="small" type="primary" @click="handleProcess">
          <el-icon><VideoPlay /></el-icon>
          <span>处理</span>
        </el-button>
        <el-button v-if="canRetry" size="small" type="warning" @click="handleRetry">
          <el-icon><RefreshRight /></el-icon>
          <span>人工重试</span>
        </el-button>
        <el-tag
          :type="statusMeta[caseItem.status]?.type ?? 'info'"
          size="large"
          effect="dark"
          round
        >
          {{ statusMeta[caseItem.status]?.text ?? caseItem.status }}
        </el-tag>
        <span v-if="report?.riskLevel" class="rk" :class="riskMeta[report.riskLevel]?.cls">
          {{ report.riskLevel }}
        </span>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">工单 #{{ caseItem.id }} · {{ caseItem.customerName }}（{{ caseItem.customerId }}）</h3>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="预警规则">{{ caseItem.alertRule }}</el-descriptions-item>
        <el-descriptions-item label="模型原始评级">
          <span v-if="caseItem.rawRiskLevel" class="rk" :class="riskMeta[caseItem.rawRiskLevel]?.cls">{{ caseItem.rawRiskLevel }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="最终评级">
          <span v-if="caseItem.riskLevel" class="rk" :class="riskMeta[caseItem.riskLevel]?.cls">{{ caseItem.riskLevel }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="执行版本"><span class="mono-num">v{{ caseItem.executionVersion }}</span></el-descriptions-item>
        <el-descriptions-item label="复核版本"><span class="mono-num">v{{ caseItem.reviewRevision }}</span></el-descriptions-item>
        <el-descriptions-item label="快照 ID"><span class="mono-num snap">{{ snapPrefix(caseItem.snapshotId) }}</span></el-descriptions-item>
        <el-descriptions-item v-if="caseItem.failureMessage" label="失败原因" :span="3">
          {{ caseItem.failureMessage }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">
          <span class="mono-num time">{{ fmtDateTime(caseItem.createdAt) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="模型">
          <span class="mono-num">{{ caseItem.modelName || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源">
          <span v-if="caseItem.reportSource" class="src">{{ caseItem.reportSource === 'AGENT' ? 'Agent 生成' : '规则降级' }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="card">
      <h3 class="card-title">Agent 工作流</h3>
      <div class="flow">
        <div
          v-for="(s, i) in workflowStages"
          :key="s.key"
          class="flow-node"
          :class="{ done: isDone(s.key), active: isActive(s.key) }"
        >
          <div class="node-circle">
            <span v-if="isDone(s.key)">✓</span>
            <span v-else>{{ i + 1 }}</span>
          </div>
          <div class="node-label">{{ s.label }}</div>
          <div class="node-desc">{{ s.desc }}</div>
          <div v-if="i < workflowStages.length - 1" class="node-link" :class="{ done: isDone(s.key) }"></div>
        </div>
      </div>
      <el-collapse v-model="logOpen" class="log-list">
        <el-collapse-item title="阶段日志（实时）" name="log">
          <div v-if="logs.length === 0" class="log-empty">暂无日志</div>
          <div v-for="(l, i) in logs" :key="i" class="log-line">
            <el-tag size="small" effect="plain">{{ l.stage }}</el-tag>
            <span class="log-time">{{ l.at }}</span>
            <span class="log-content">{{ l.content }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div v-if="streamingText" class="card">
      <h3 class="card-title">可选 AI 分析摘要</h3>
      <div class="streaming-text">{{ streamingText }}<span class="cursor">▍</span></div>
      <p class="hint">此为独立生成的分析摘要，非主 Agent 内部推理过程。</p>
    </div>

    <div v-if="report" class="card">
      <h3 class="card-title">尽调初审报告</h3>
      <div class="report">
        <div v-if="caseItem?.reportSource || caseItem?.snapshotId" class="report-row">
          <div class="report-label">执行溯源</div>
          <div class="trace-line">
            <span class="src" :class="caseItem?.reportSource === 'AGENT' ? 'src-agent' : 'src-rule'">
              {{ caseItem?.reportSource === 'AGENT' ? 'Agent 生成' : '规则降级' }}
            </span>
            <span v-if="caseItem?.snapshotId" class="mono-num trace-id">快照 {{ snapPrefix(caseItem.snapshotId) }}</span>
          </div>
        </div>

        <div class="report-row">
          <div class="report-label">风险评级</div>
          <span class="rk" :class="riskMeta[report.riskLevel]?.cls">{{ report.riskLevel }}</span>
          <span v-if="report.manualReviewRequired" class="need-review">需人工复核</span>
        </div>

        <div v-if="report.findingCodes?.length" class="report-row">
          <div class="report-label">风险发现代码</div>
          <div class="code-chips">
            <span v-for="c in report.findingCodes" :key="c" class="code-chip warn">{{ c }}</span>
          </div>
        </div>

        <div v-if="report.actionCodes?.length" class="report-row">
          <div class="report-label">处置代码</div>
          <div class="code-chips">
            <span
              v-for="c in report.actionCodes"
              :key="c"
              class="code-chip"
              :class="c === 'MANUAL_REVIEW' ? 'danger' : 'info'"
            >{{ c }}</span>
          </div>
        </div>

        <div class="report-row">
          <div class="report-label">风险点</div>
          <ul class="risk-list">
            <li v-for="(p, i) in report.riskPoints" :key="i">{{ p }}</li>
          </ul>
        </div>

        <div class="report-row">
          <div class="report-label">交易画像</div>
          <pre class="mono">{{ report.transactionProfile }}</pre>
        </div>

        <div class="report-row">
          <div class="report-label">股权穿透 / UBO</div>
          <pre class="mono">{{ report.corporateProfile }}</pre>
        </div>

        <div v-if="report.sanctions.length" class="report-row">
          <div class="report-label">黑名单命中</div>
          <ul class="risk-list sanction">
            <li v-for="(s, i) in report.sanctions" :key="i">{{ s }}</li>
          </ul>
        </div>

        <div v-if="report.legalBasis.length" class="report-row">
          <div class="report-label">法规依据（RAG）</div>
          <el-collapse>
            <el-collapse-item v-for="(b, i) in report.legalBasis" :key="i" :title="legalTitle(b)">
              <div class="legal-body">{{ legalBody(b) }}</div>
            </el-collapse-item>
          </el-collapse>
        </div>

        <div class="report-row">
          <div class="report-label">结论与建议</div>
          <div class="conclusion">{{ report.conclusion }}</div>
        </div>

        <div v-if="report.evidenceChain.length" class="report-row">
          <div class="report-label">证据链</div>
          <div class="evidence">
            <span v-for="(e, i) in report.evidenceChain" :key="i" class="ev-chip">{{ e }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* 加载态 / 错误态 */
.detail-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 80px 0;
  color: var(--text-dim);
  font-size: 14px;
}
.load-icon {
  font-size: 22px;
  color: var(--gold);
}
.detail-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 80px 0;
}
.err-mark {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 26px;
  color: var(--risk-high);
  border: 2px solid rgba(196, 61, 75, 0.4);
  background: rgba(196, 61, 75, 0.08);
}
.err-msg {
  margin: 0;
  color: var(--text-dim);
  font-size: 14px;
}

.detail-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 2px;
}

.bar-right {
  display: flex;
  gap: 10px;
  align-items: center;
}

.rk {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 999px;
  display: inline-block;
}
.rk-high { color: #c43d4b; background: rgba(196, 61, 75, 0.14); border: 1px solid rgba(196, 61, 75, 0.35); }
.rk-mid { color: #e0a23a; background: rgba(224, 162, 58, 0.14); border: 1px solid rgba(224, 162, 58, 0.35); }
.rk-low { color: #2fa37f; background: rgba(47, 163, 127, 0.14); border: 1px solid rgba(47, 163, 127, 0.35); }

.snap, .time, .mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.snap { color: var(--gold); }
.time { color: var(--text-dim); font-size: 12px; }

.src {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 6px;
  border: 1px solid var(--line);
}
.src-agent { color: var(--gold); background: rgba(201, 169, 97, 0.1); }
.src-rule { color: var(--risk-mid); background: rgba(224, 162, 58, 0.1); }

/* 工作流 */
.flow {
  display: flex;
  gap: 0;
  margin-bottom: 14px;
  padding: 8px 0;
  overflow-x: auto;
}

.flow-node {
  position: relative;
  flex: 1;
  min-width: 96px;
  text-align: center;
  padding: 0 6px;
}

.node-circle {
  width: 34px;
  height: 34px;
  margin: 0 auto 8px;
  border-radius: 50%;
  border: 2px solid #2b3a57;
  background: var(--bg-panel);
  color: var(--text-faint);
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  position: relative;
  z-index: 2;
}

.flow-node.active .node-circle {
  border-color: var(--gold);
  color: var(--gold);
  box-shadow: 0 0 0 4px rgba(201, 169, 97, 0.15), 0 0 16px rgba(201, 169, 97, 0.25);
  animation: nodepulse 1.4s ease-in-out infinite;
}

.flow-node.done .node-circle {
  border-color: var(--risk-low);
  background: rgba(47, 163, 127, 0.16);
  color: var(--risk-low);
}

@keyframes nodepulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.08); }
}

.node-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}

.node-desc {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 3px;
  line-height: 1.4;
}

.node-link {
  position: absolute;
  top: 17px;
  left: 50%;
  width: 100%;
  height: 2px;
  background: #24314a;
  z-index: 1;
}
.node-link.done { background: rgba(47, 163, 127, 0.5); }

/* 日志 */
.log-empty { color: var(--text-faint); font-size: 13px; }
.log-line {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--line-faint);
  font-size: 13px;
}
.log-time {
  color: var(--text-faint);
  min-width: 78px;
  font-family: var(--font-mono);
  font-size: 12px;
}
.log-content {
  color: var(--text-dim);
  line-height: 1.7;
}

/* 报告 */
.report-row {
  display: flex;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line-faint);
}
.report-row:last-child { border-bottom: none; }
.report-label {
  width: 110px;
  flex-shrink: 0;
  font-weight: 600;
  color: var(--gold);
  font-size: 13px;
  padding-top: 2px;
  letter-spacing: 0.02em;
}
.need-review {
  font-size: 12px;
  color: #c43d4b;
  font-weight: 600;
  background: rgba(196, 61, 75, 0.12);
  border: 1px solid rgba(196, 61, 75, 0.3);
  border-radius: 999px;
  padding: 2px 12px;
}
.trace-line { display: flex; gap: 10px; align-items: center; }
.trace-id { font-size: 12px; color: var(--text-dim); }

.code-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.code-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 6px;
  border: 1px solid var(--line);
  color: var(--text-dim);
  background: rgba(11, 18, 32, 0.4);
}
.code-chip.warn { color: var(--risk-mid); border-color: rgba(224, 162, 58, 0.3); }
.code-chip.danger { color: var(--risk-high); border-color: rgba(196, 61, 75, 0.3); }
.code-chip.info { color: var(--risk-info); border-color: rgba(74, 158, 255, 0.3); }

.risk-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text);
  line-height: 1.9;
}
.risk-list.sanction li { color: var(--risk-high); }

.mono {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  background: rgba(11, 18, 32, 0.5);
  border: 1px solid var(--line-faint);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-dim);
  flex: 1;
}

.legal-body { line-height: 1.8; color: var(--text-dim); }
.conclusion { line-height: 1.8; color: var(--text); }

.evidence { display: flex; flex-wrap: wrap; gap: 8px; }
.ev-chip {
  font-size: 12px;
  color: var(--text-dim);
  background: rgba(11, 18, 32, 0.5);
  border: 1px solid var(--line);
  padding: 3px 10px;
  border-radius: 6px;
}

.streaming-text {
  line-height: 1.9;
  color: var(--text);
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-all;
}
.cursor {
  color: var(--gold);
  animation: blink 1s step-end infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

@media (max-width: 860px) {
  .detail-bar { flex-wrap: wrap; }
  .report-row { flex-direction: column; gap: 6px; }
  .report-label { width: auto; }
}
</style>
