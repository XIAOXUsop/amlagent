<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  fmtDateTime,
  getCaseDossier,
  getCase,
  listLogs,
  listToolTraces,
  parseReport,
  processCase,
  retryCase,
  reviewSanctionCandidate,
  screenSanctions,
  subscribeCase,
  type CaseItem,
  type DueDiligenceReport,
  type SanctionScreeningResult,
  type SanctionCandidateMatch,
  type ToolTrace,
  type WorkflowEvent,
} from '../api/client'
import { riskMeta, statusMeta, TERMINAL_STATUSES } from '../constants/case'
import { Back, Download, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import { currentUser } from '../auth'

const props = defineProps<{ caseId: number }>()
const emit = defineEmits<{ (e: 'back'): void }>()

const caseItem = ref<CaseItem | null>(null)
const report = ref<DueDiligenceReport | null>(null)
const loadError = ref(false)
const detailLoading = ref(true)
const logs = ref<{ stage: string; content: string; at: string }[]>([])
const toolTraces = ref<ToolTrace[]>([])
const sanctionScreening = ref<SanctionScreeningResult | null>(null)
const screeningLoading = ref(false)
const dossierLoading = ref(false)
const reviewingFingerprint = ref('')
const doneKeys = ref<Set<string>>(new Set())
const currentKey = ref('')
const logOpen = ref<string[]>(['log'])
const streamingText = ref('')
const sseState = ref<'connecting' | 'open' | 'reconnecting' | 'closed'>('connecting')

let unsubscribe: (() => void) | null = null
let refreshTimer: number | null = null
/** 连接序号：并发 connect() 时只有最新一次生效，防止旧订阅写回已重置的页面状态 */
let connectSeq = 0
/** 历史 + 实时日志去重（后端 SSE 事件与 case_log 落库内容一致） */
let seenKeys = new Set<string>()

const workflowStages = [
  { key: 'PLANNING', label: '任务规划', desc: '解析预警工单，拆解子任务' },
  { key: 'COLLECTING', label: '数据采集', desc: '并行调用多源数据工具' },
  { key: 'REASONING', label: '风险推理', desc: '综合研判风险特征' },
  { key: 'GUARDRAIL', label: '规则护栏', desc: '制裁名单 / 评级一致性校验' },
  { key: 'REPORTING', label: '报告生成', desc: '结构化尽调初审报告' },
  { key: 'DONE', label: '完成', desc: '归档 / 转人工' },
]

function clearRefreshTimer() {
  if (refreshTimer !== null) {
    window.clearTimeout(refreshTimer)
    refreshTimer = null
  }
}

/** 合并实时与历史日志：按 stage|content 去重、限制条数上限，避免重复与无限增长 */
function pushLog(stage: string, content: string, at: string) {
  const key = `${stage}|${content}`
  if (seenKeys.has(key)) return
  seenKeys.add(key)
  logs.value.push({ stage, content, at })
  if (logs.value.length > 300) logs.value.shift()
  currentKey.value = stage
  doneKeys.value.add(stage)
  // REPORTING 到达时 REASONING 已完成（后端保证各阶段按序发事件，此处仅兜底标记）
  if (stage === 'REPORTING') doneKeys.value.add('REASONING')
  if (stage === 'DONE') {
    workflowStages.forEach((s) => doneKeys.value.add(s.key))
    doneKeys.value.add('REASONING')
  }
}

function handleEvent(ev: WorkflowEvent) {
  pushLog(ev.stage, ev.content, new Date().toLocaleTimeString())
  // 终态事件后延迟拉取一次详情，对齐报告/状态字段（定时器统一在重连/卸载时清理）
  if ((TERMINAL_STATUSES as readonly string[]).includes(ev.stage)) {
    scheduleRefresh()
  }
}

function scheduleRefresh() {
  clearRefreshTimer()
  refreshTimer = window.setTimeout(refresh, 800)
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
  }
}

async function connect() {
  const seq = ++connectSeq
  unsubscribe?.()
  clearRefreshTimer()
  logs.value = []
  seenKeys = new Set()
  doneKeys.value = new Set()
  currentKey.value = ''
  streamingText.value = ''
  toolTraces.value = []
  sanctionScreening.value = null
  sseState.value = 'connecting'
  detailLoading.value = true

  // 先订阅实时事件，再拉历史：避免"拉取完成才订阅"的空窗丢事件；
  // 历史与订阅窗口内的实时事件按 stage|content 去重合并。
  unsubscribe = subscribeCase(
    props.caseId,
    handleEvent,
    (token) => {
      streamingText.value += token
    },
    (state) => {
      sseState.value = state
    },
  )

  try {
    const c = await getCase(props.caseId)
    if (seq !== connectSeq) return
    caseItem.value = c
    report.value = parseReport(c)
    loadError.value = false
  } catch {
    if (seq !== connectSeq) return
    loadError.value = true
  } finally {
    if (seq === connectSeq) detailLoading.value = false
  }
  if (seq !== connectSeq) return

  try {
    const history = await listLogs(props.caseId)
    if (seq !== connectSeq) return
    history.forEach((l) => pushLog(l.stage, l.content, ''))
  } catch {
    // 历史日志拉取失败不影响订阅实时进度
    if (seq !== connectSeq) return
    if (!logs.value.length) {
      logs.value.push({ stage: 'PLANNING', content: '历史日志加载失败，正在订阅实时进度…', at: '' })
    }
  }

  try {
    const traces = await listToolTraces(props.caseId)
    if (seq !== connectSeq) return
    toolTraces.value = traces
  } catch {
    // 工具轨迹加载失败不影响主流程（日志/报告仍可查看）
    if (seq !== connectSeq) return
    toolTraces.value = []
  }

  if (caseItem.value) {
    screeningLoading.value = true
    try {
      const screening = await screenSanctions(caseItem.value.customerId)
      if (seq !== connectSeq) return
      sanctionScreening.value = screening
    } catch {
      if (seq !== connectSeq) return
      sanctionScreening.value = null
    } finally {
      if (seq === connectSeq) screeningLoading.value = false
    }
  }
}

// 同一组件实例在 /cases/1 → /cases/2 间复用（浏览器前进/后退）时重建订阅，避免展示旧工单数据
watch(() => props.caseId, () => connect())

onMounted(connect)

onUnmounted(() => {
  clearRefreshTimer()
  unsubscribe?.()
})

async function retryLoad() {
  loadError.value = false
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

async function handleDossierDownload() {
  dossierLoading.value = true
  try {
    const dossier = await getCaseDossier(props.caseId)
    const blob = new Blob([JSON.stringify(dossier, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `aml-case-${props.caseId}-dossier.json`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success(`调查档案已导出，完整性摘要 ${dossier.contentHash.slice(0, 12)}…`)
  } catch {
    ElMessage.error('调查档案导出失败，请稍后重试')
  } finally {
    dossierLoading.value = false
  }
}

function screeningTagType(decision: string): 'danger' | 'warning' | 'info' {
  if (decision === 'CONFIRMED') return 'danger'
  if (decision === 'REVIEW_REQUIRED') return 'warning'
  return 'info'
}

function screeningDecisionText(decision: string): string {
  if (decision === 'CONFIRMED') return '确定命中'
  if (decision === 'REVIEW_REQUIRED') return '待人工核验'
  return '已排除'
}

const canRetry = computed(() => caseItem.value && caseItem.value.status === 'FAILED')
const canProcess = computed(() => caseItem.value && caseItem.value.status === 'PENDING')
const canReviewSanctions = computed(() => ['REVIEWER', 'ADMIN'].includes(currentUser.value?.role ?? ''))

async function handleCandidateReview(
  candidate: SanctionCandidateMatch,
  decision: 'CONFIRM' | 'DISMISS' | 'REQUEST_MORE_INFO',
) {
  const actionLabel = decision === 'CONFIRM' ? '确认命中' : decision === 'DISMISS' ? '排除候选' : '要求补充材料'
  try {
    const { value } = await ElMessageBox.prompt(
      `将“${candidate.candidateName}”标记为：${actionLabel}`,
      '制裁候选人工核验',
      {
        confirmButtonText: '提交核验',
        cancelButtonText: '取消',
        inputPlaceholder: '请填写判断依据（必填）',
        inputValidator: (text: string) => {
          if (!text?.trim()) return '人工核验必须填写判断依据'
          if (text && text.length > 500) return '核验意见最多 500 字'
          return true
        },
      },
    )
    reviewingFingerprint.value = candidate.candidateFingerprint
    sanctionScreening.value = await reviewSanctionCandidate(caseItem.value!.customerId, {
      candidateFingerprint: candidate.candidateFingerprint,
      decision,
      comment: value?.trim() ?? '',
      expectedRevision: candidate.reviewRevision,
    })
    ElMessage.success('候选核验决定已保存')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    if (error?.response?.status === 409) {
      ElMessage.warning('候选已被其他复核人更新，正在刷新最新结果')
      sanctionScreening.value = await screenSanctions(caseItem.value!.customerId)
    } else {
      ElMessage.error('候选核验提交失败，请稍后重试')
    }
  } finally {
    reviewingFingerprint.value = ''
  }
}

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
        <el-button size="small" :loading="dossierLoading" @click="handleDossierDownload">
          <el-icon><Download /></el-icon>
          <span>导出调查档案</span>
        </el-button>
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
      <h3 class="card-title screening-title">
        可解释制裁筛查
        <el-tag v-if="sanctionScreening" size="small"
          :type="sanctionScreening.status === 'CONFIRMED_MATCH' ? 'danger' : sanctionScreening.status === 'REVIEW_REQUIRED' ? 'warning' : 'success'">
          {{ sanctionScreening.status === 'CONFIRMED_MATCH' ? '存在确定命中' : sanctionScreening.status === 'REVIEW_REQUIRED' ? '需要人工核验' : '未发现命中' }}
        </el-tag>
      </h3>
      <div v-if="screeningLoading" class="log-empty">正在核验名单候选…</div>
      <div v-else-if="!sanctionScreening" class="log-empty">筛查服务暂不可用，不影响已归档的尽调报告。</div>
      <template v-else>
        <div class="screening-meta">
          数据源 {{ sanctionScreening.sourceSystem }} / {{ sanctionScreening.sourceVersion }} ·
          {{ fmtDateTime(sanctionScreening.screenedAt) }}
        </div>
        <div v-if="sanctionScreening.candidates.length === 0" class="screening-empty">未召回同名或同证件号候选</div>
        <div v-for="candidate in sanctionScreening.candidates" :key="`${candidate.listType}-${candidate.candidateName}`" class="candidate-row">
          <div class="candidate-score" :class="`score-${candidate.decision.toLowerCase()}`">{{ candidate.score }}</div>
          <div class="candidate-main">
            <div class="candidate-head">
              <strong>{{ candidate.candidateName }}</strong>
              <el-tag size="small" :type="screeningTagType(candidate.decision)" effect="plain">
                {{ screeningDecisionText(candidate.decision) }}
              </el-tag>
              <span class="candidate-list">{{ candidate.listType }} · 级别 {{ candidate.severity }}</span>
            </div>
            <div class="candidate-explain">{{ candidate.explanation }}</div>
            <div v-if="candidate.reviewDecision" class="candidate-review">
              <strong>人工核验：</strong>
              {{ candidate.reviewDecision === 'CONFIRM' ? '确认命中' : candidate.reviewDecision === 'DISMISS' ? '已排除' : '等待补充材料' }}
              · v{{ candidate.reviewRevision }} · {{ candidate.reviewedBy }} · {{ fmtDateTime(candidate.reviewedAt) }}
              <span v-if="candidate.reviewComment">（{{ candidate.reviewComment }}）</span>
            </div>
            <div class="candidate-foot">
              <span>身份标识 {{ candidate.identityMasked }}</span>
              <span v-for="code in candidate.reasonCodes" :key="code" class="code-chip info">{{ code }}</span>
            </div>
            <div v-if="canReviewSanctions" class="candidate-actions">
              <el-button size="small" type="danger" plain
                :loading="reviewingFingerprint === candidate.candidateFingerprint"
                @click="handleCandidateReview(candidate, 'CONFIRM')">确认命中</el-button>
              <el-button size="small" type="success" plain
                :disabled="!!reviewingFingerprint"
                @click="handleCandidateReview(candidate, 'DISMISS')">排除候选</el-button>
              <el-button size="small" type="warning" plain
                :disabled="!!reviewingFingerprint"
                @click="handleCandidateReview(candidate, 'REQUEST_MORE_INFO')">补充材料</el-button>
            </div>
          </div>
        </div>
      </template>
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
      <h3 class="card-title">
        Agent 工作流
        <span class="sse-indicator" :class="sseState">
          <i class="sse-dot"></i>
          <span>{{ sseState === 'open' ? '实时连接' : sseState === 'reconnecting' ? '连接中断，正在重连…' : sseState === 'closed' ? '流程已结束' : '正在连接…' }}</span>
        </span>
      </h3>
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
          <el-collapse-item title="工具调用轨迹" name="tools">
            <div v-if="toolTraces.length === 0" class="log-empty">暂无工具调用记录</div>
            <div v-for="t in toolTraces" :key="`${t.executionVersion}-${t.sequenceNo}`" class="log-line tool-line">
              <el-tag size="small" :type="t.success ? 'success' : t.argumentValid ? 'danger' : 'warning'" effect="plain">
                {{ t.toolName }}
              </el-tag>
              <span class="log-time">v{{ t.executionVersion }} · {{ t.durationMs }}ms</span>
              <span class="log-content">
                {{ t.success ? '成功' : (t.argumentValid ? `失败（${t.errorCode ?? 'ERROR'}）` : '参数校验未通过') }}
                <span v-if="t.resultDigest" class="trace-digest">#{{ t.resultDigest }}</span>
              </span>
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
  gap: 0;
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
  margin-bottom: 0;
  padding-bottom: 22px;
}

.bar-right {
  display: flex;
  gap: 10px;
  align-items: center;
}

.screening-title { display: flex; align-items: center; gap: 10px; }
.screening-meta { color: var(--text-faint); font-size: 12px; margin-bottom: 10px; }
.screening-empty { color: var(--risk-low); font-size: 13px; padding: 10px 0; }
.candidate-row {
  display: flex;
  gap: 14px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line-faint);
}
.candidate-row:last-child { border-bottom: none; }
.candidate-score {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-family: var(--font-mono);
  font-weight: 700;
  border: 1px solid var(--line);
}
.score-confirmed { color: var(--risk-high); background: rgba(196, 61, 75, 0.12); }
.score-review_required { color: var(--risk-mid); background: rgba(224, 162, 58, 0.12); }
.score-dismissed { color: var(--text-faint); background: rgba(124, 139, 163, 0.08); }
.candidate-main { flex: 1; min-width: 0; }
.candidate-head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.candidate-list { color: var(--text-faint); font-size: 12px; }
.candidate-explain { color: var(--text-dim); font-size: 13px; margin: 7px 0; line-height: 1.6; }
.candidate-review {
  color: var(--text-dim);
  font-size: 12px;
  line-height: 1.6;
  margin: 7px 0;
  padding: 7px 9px;
  border-left: 2px solid var(--gold);
  background: #f8fafc;
}
.candidate-foot { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; color: var(--text-faint); font-size: 11px; }
.candidate-actions { display: flex; gap: 6px; margin-top: 10px; flex-wrap: wrap; }

.rk {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 999px;
  display: inline-block;
}
.rk-high { color: var(--risk-high); background: #fef3f2; border: 1px solid #fecdca; }
.rk-mid { color: var(--risk-mid); background: #fffaeb; border: 1px solid #fedf89; }
.rk-low { color: var(--risk-low); background: #ecfdf3; border: 1px solid #abefc6; }

.snap, .time, .mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.snap { color: var(--text-dim); }
.time { color: var(--text-dim); font-size: 12px; }

.src {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 6px;
  border: 1px solid var(--line);
}
.src-agent { color: var(--text-dim); background: #f8fafc; }
.src-rule { color: var(--risk-mid); background: #fffaeb; }

/* 工作流 */
.sse-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 12px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-faint);
  vertical-align: middle;
}
.sse-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-faint);
}
.sse-indicator.open .sse-dot {
  background: var(--risk-low);
  box-shadow: 0 0 0 3px rgba(47, 163, 127, 0.18);
}
.sse-indicator.open { color: var(--risk-low); }
.sse-indicator.reconnecting .sse-dot {
  background: var(--risk-mid);
  box-shadow: 0 0 0 3px rgba(224, 162, 58, 0.18);
  animation: nodepulse 1.2s ease-in-out infinite;
}
.sse-indicator.reconnecting { color: var(--risk-mid); }

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
  border: 1px solid var(--line-strong);
  background: #ffffff;
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
  box-shadow: 0 0 0 3px #dbeafe;
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
  background: var(--line);
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
  color: var(--text-dim);
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
  background: #f8fafc;
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
  background: #f8fafc;
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
  background: #f8fafc;
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
  color: var(--risk-info);
  animation: blink 1s step-end infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

@media (max-width: 860px) {
  .detail-bar { flex-wrap: wrap; }
  .report-row { flex-direction: column; gap: 6px; }
  .report-label { width: auto; }
}
</style>
