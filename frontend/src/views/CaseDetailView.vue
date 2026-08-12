<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
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
import { ElMessage } from 'element-plus'

const props = defineProps<{ caseId: number }>()
const emit = defineEmits<{ (e: 'back'): void }>()

const caseItem = ref<CaseItem | null>(null)
const report = ref<DueDiligenceReport | null>(null)
const logs = ref<{ stage: string; content: string; at: string }[]>([])
const doneKeys = ref<Set<string>>(new Set())
const currentKey = ref('')
const logOpen = ref<string[]>(['log'])
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

const riskMeta: Record<string, { text: string; type: 'success' | 'warning' | 'danger' }> = {
  低风险: { text: '低风险', type: 'success' },
  中风险: { text: '中风险', type: 'warning' },
  高风险: { text: '高风险', type: 'danger' },
}

function handleEvent(ev: WorkflowEvent) {
  logs.value.push({ stage: ev.stage, content: ev.content, at: new Date().toLocaleTimeString() })
  currentKey.value = ev.stage
  // REASONING 无独立事件：收到 REPORTING 时视为推理完成
  if (ev.stage === 'REPORTING') {
    doneKeys.value.add('REASONING')
  }
  if (ev.stage === 'DONE') {
    // 兜底：全部点亮
    workflowStages.forEach((s) => doneKeys.value.add(s.key))
    doneKeys.value.add('REASONING')
  }
  doneKeys.value.add(ev.stage)
  // 终态后拉取最终报告
  if (['DONE', 'HOLD', 'FAILED'].includes(ev.stage)) {
    setTimeout(refresh, 800)
  }
}

async function refresh() {
  try {
    const c = await getCase(props.caseId)
    caseItem.value = c
    report.value = parseReport(c)
  } catch {
    /* ignore */
  }
}

async function connect() {
  unsubscribe?.()
  logs.value = []
  doneKeys.value = new Set()
  currentKey.value = ''
  await refresh()
  const history = await listLogs(props.caseId)
  history.forEach((l) => {
    if (['DONE', 'HOLD', 'FAILED'].includes(l.stage)) {
      workflowStages.forEach((s) => doneKeys.value.add(s.key))
    }
    doneKeys.value.add(l.stage)
    logs.value.push({ stage: l.stage, content: l.content, at: '' })
    currentKey.value = l.stage
  })
  unsubscribe = subscribeCase(props.caseId, handleEvent)
}

onMounted(connect)

onUnmounted(() => {
  unsubscribe?.()
})

async function handleRetry() {
  await retryCase(props.caseId)
  ElMessage.success('已重新入队，正在执行')
  await connect()
}

async function handleProcess() {
  await processCase(props.caseId)
  ElMessage.success('已触发尽调')
  await connect()
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
</script>

<template>
  <div v-if="caseItem">
    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px">
      <el-button @click="emit('back')">返回列表</el-button>
      <div style="display: flex; gap: 10px; align-items: center">
        <el-button v-if="canProcess" size="small" type="primary" @click="handleProcess">处理</el-button>
        <el-button v-if="canRetry" size="small" type="warning" @click="handleRetry">人工重试</el-button>
        <el-tag :type="statusMeta[caseItem.status]?.type ?? 'info'" size="large">
          {{ statusMeta[caseItem.status]?.text ?? caseItem.status }}
        </el-tag>
        <el-tag v-if="report?.riskLevel" :type="riskMeta[report.riskLevel]?.type ?? 'info'" size="large">
          {{ report.riskLevel }}
        </el-tag>
      </div>
    </div>

    <div class="card">
      <h3 class="card-title">工单 #{{ caseItem.id }} · {{ caseItem.customerName }}（{{ caseItem.customerId }}）</h3>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="预警规则">{{ caseItem.alertRule }}</el-descriptions-item>
        <el-descriptions-item label="最终评级">{{ caseItem.riskLevel ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="执行版本">{{ caseItem.executionVersion }}</el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ caseItem.retryCount }}</el-descriptions-item>
        <el-descriptions-item v-if="caseItem.failureMessage" label="失败原因" :span="2">
          {{ caseItem.failureMessage }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ caseItem.createdAt.replace('T', ' ').slice(0, 19) }}</el-descriptions-item>
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
          <div v-if="logs.length === 0" style="color: #909399; font-size: 13px">暂无日志</div>
          <div v-for="(l, i) in logs" :key="i" class="log-line">
            <el-tag size="small" effect="plain">{{ l.stage }}</el-tag>
            <span class="log-time">{{ l.at }}</span>
            <span class="log-content" style="white-space: pre-wrap">{{ l.content }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div v-if="report" class="card">
      <h3 class="card-title">尽调初审报告</h3>
      <div class="report">
        <div class="report-row">
          <div class="report-label">风险评级</div>
          <el-tag :type="riskMeta[report.riskLevel]?.type ?? 'info'" size="large">{{ report.riskLevel }}</el-tag>
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
          <ul class="risk-list">
            <li v-for="(s, i) in report.sanctions" :key="i">{{ s }}</li>
          </ul>
        </div>

        <div v-if="report.legalBasis.length" class="report-row">
          <div class="report-label">法规依据（RAG）</div>
          <el-collapse>
            <el-collapse-item v-for="(b, i) in report.legalBasis" :key="i" :title="legalTitle(b)">
              <div style="line-height: 1.8">{{ legalBody(b) }}</div>
            </el-collapse-item>
          </el-collapse>
        </div>

        <div class="report-row">
          <div class="report-label">结论与建议</div>
          <div style="line-height: 1.8">{{ report.conclusion }}</div>
        </div>

        <div v-if="report.evidenceChain.length" class="report-row">
          <div class="report-label">证据链</div>
          <div style="display: flex; flex-wrap: wrap; gap: 8px">
            <el-tag v-for="(e, i) in report.evidenceChain" :key="i" type="info" effect="plain">{{ e }}</el-tag>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.flow {
  display: flex;
  gap: 0;
  margin-bottom: 14px;
  padding: 6px 0;
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
  margin: 0 auto 6px;
  border-radius: 50%;
  border: 2px solid #c0c4cc;
  background: #fff;
  color: #909399;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  position: relative;
  z-index: 2;
}

.flow-node.active .node-circle {
  border-color: #409eff;
  color: #409eff;
  box-shadow: 0 0 0 4px rgba(64, 158, 255, 0.15);
  animation: pulse 1.2s infinite;
}

.flow-node.done .node-circle {
  border-color: #67c23a;
  background: #67c23a;
  color: #fff;
}

.node-label {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.node-desc {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  line-height: 1.4;
}

.node-link {
  position: absolute;
  top: 17px;
  left: 50%;
  width: 100%;
  height: 2px;
  background: #dcdfe6;
  z-index: 1;
}

.node-link.done {
  background: #67c23a;
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(64, 158, 255, 0.3);
  }
  70% {
    box-shadow: 0 0 0 6px rgba(64, 158, 255, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(64, 158, 255, 0);
  }
}

.log-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed #f0f0f0;
  font-size: 13px;
}

.log-time {
  color: #909399;
  min-width: 76px;
}

.log-content {
  color: #606266;
  line-height: 1.6;
}

.report-row {
  display: flex;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f2f5;
}

.report-row:last-child {
  border-bottom: none;
}

.report-label {
  width: 110px;
  flex-shrink: 0;
  font-weight: 600;
  color: #1f4e79;
  font-size: 13px;
  padding-top: 2px;
}

.risk-list {
  margin: 0;
  padding-left: 18px;
  color: #303133;
  line-height: 1.9;
}

.mono {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: #303133;
  flex: 1;
}
</style>
