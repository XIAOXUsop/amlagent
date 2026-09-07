<script setup lang="ts">
/**
 * 解释核验工作区（v2 计划 §14）：围绕问题办事，支持同案混合结论。
 * 页面依据 allowedActions 展示入口；最终校验由后端在案件锁内重新执行。
 * 冲突/令牌失效沿用统一协议：保留草稿、展示最新事实、由用户明确确认后再提交。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  amendExplanationUnit, captureExplanationEvidence, disposeExplanationIssue,
  getExplanationWorkspace, saveExplanationDraft, submitExplanationUnit,
  type ExplanationIssueView, type ExplanationUnitView, type ExplanationWorkspaceView,
} from '../api/client'
import {
  dispositionText, isExplanationRevisionConflict, outcomeText, outcomeTagType,
  policyQuestionFocus, QUESTION_CODES, severityText, type IssueDisposition,
} from '../utils/explanation'

const props = defineProps<{ caseId: number }>()

const workspace = ref<ExplanationWorkspaceView | null>(null)
const loading = ref(false)
const actionUnitId = ref<number | null>(null)
const expandedDraft = ref<number | null>(null)

const draftText = ref('')
const draftRevision = ref(0)

const explainCounts = computed(() => {
  const units = workspace.value?.units ?? []
  return {
    explained: units.filter((unit) => unit.currentOutcome === 'EXPLAINED').length,
    suspicious: units.filter((unit) => unit.currentOutcome === 'SUSPICIOUS').length,
    unresolved: units.filter((unit) => !unit.currentOutcome || unit.currentOutcome === 'UNRESOLVED').length,
    total: units.length,
  }
})

async function reload() {
  loading.value = true
  try {
    workspace.value = await getExplanationWorkspace(props.caseId)
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '工作区加载失败，请刷新后重试')
  } finally {
    loading.value = false
  }
}

onMounted(reload)

function openDraft(unit: ExplanationUnitView) {
  expandedDraft.value = unit.unitId
  ElMessageBox.prompt(
    '粘贴/编辑六问题草稿 JSON（policy/scope/questions/outcome）；页面提供结构化编辑前的演示入口。'
      + '\n提示：结论由服务端按配方与核验约束校验，前端不做门禁判断。',
    `编辑预警 ${unit.externalAlertId ?? unit.alertId} 的解释草稿`,
    {
      inputType: 'textarea',
      inputValue: draftText.value || '',
      inputValidator: (text: string) => (text?.trim().length >= 10) || '草稿 JSON 至少 10 个字符',
    },
  ).then(({ value }) => {
    draftText.value = value.trim()
  }).catch(() => undefined)
}

async function saveDraft(unit: ExplanationUnitView) {
  if (draftText.value.trim().length < 10) {
    ElMessage.warning('请先在“编辑草稿”中填写草稿 JSON')
    return
  }
  actionUnitId.value = unit.unitId
  try {
    const updated = await saveExplanationDraft(props.caseId, unit.unitId, {
      expectedDraftRevision: unit.draftRevision,
      draftJson: draftText.value.trim(),
    })
    draftRevision.value = updated.draftRevision
    ElMessage.success('草稿已保存（草稿不产生最终业务判断）')
    await reload()
  } catch (error: any) {
    if (isExplanationRevisionConflict(error)) {
      ElMessage.warning('草稿已被他人更新，请刷新后重新打开编辑（你粘贴的内容仍在本页）')
    } else {
      ElMessage.error(error?.response?.data?.message ?? '草稿保存失败，请刷新后重试')
    }
  } finally {
    actionUnitId.value = null
  }
}

async function submitUnit(unit: ExplanationUnitView) {
  actionUnitId.value = unit.unitId
  try {
    const result = await submitExplanationUnit(props.caseId, unit.unitId, {
      expectedDraftRevision: unit.draftRevision,
      reviewBasisToken: reviewBasisToken.value,
      idempotencyKey: `SUBMIT:${props.caseId}:${unit.unitId}:${Date.now()}`,
    })
    ElMessage.success(`提交成功：${outcomeText[result.outcome]}；假设汇总 ${outcomeText[result.hypothesisAggregate]}`)
    await reload()
  } catch (error: any) {
    if (isExplanationRevisionConflict(error)) {
      await reload()
      ElMessage.warning('案件事实已变化（令牌失效或草稿版本变化），已刷新最新事实；请核对后重新提交')
    } else {
      ElMessage.error(error?.response?.data?.message ?? '提交失败，请刷新后重试')
    }
  } finally {
    actionUnitId.value = null
  }
}

async function amend(unit: ExplanationUnitView) {
  actionUnitId.value = unit.unitId
  try {
    await ElMessageBox.confirm(
      '修订将撤回当前采用的提交并开始下一稿；待复核依据会失效（旧提交仍可回放但不可被最终采用）。确认继续？',
      '修订解释',
      { type: 'warning' },
    )
    await amendExplanationUnit(props.caseId, unit.unitId, {
      currentSubmissionId: unit.currentSubmissionId!,
    })
    ElMessage.success('已撤回当前提交；请编辑草稿后重新提交')
    await reload()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.response?.data?.message ?? '修订失败，请刷新后重试')
    }
  } finally {
    actionUnitId.value = null
  }
}

async function disposeIssue(issue: ExplanationIssueView, disposition: IssueDisposition) {
  try {
    const { value } = await ElMessageBox.prompt(
      disposition === 'RESOLVED_WITH_EVIDENCE'
        ? '请输入处置说明与证据引用（格式：说明 | 证据引用）'
        : disposition === 'DISCLOSED_UNRESOLVED'
          ? '请说明该未知的内容及其对本次判断的影响（至少 10 个字符）'
          : '请说明与本次决定不相关的理由（至少 20 个字符）',
      `处置问题：${issue.issueKey}`,
      { inputType: 'textarea' },
    )
    const [reason, evidenceReference] = value.split('|').map((part) => part?.trim() ?? '')
    await disposeExplanationIssue(props.caseId, issue.issueId, {
      expectedRevision: issue.revision,
      disposition,
      reason,
      ...(evidenceReference ? { evidenceReference } : {}),
    })
    ElMessage.success('问题处置已记录；案件事实序号已推进')
    await reload()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.response?.data?.message ?? '处置失败，请刷新后重试')
    }
  }
}

const captureForm = ref({ sourceSystem: 'CORE_BANKING', sourceReference: '', contentSha256: '' })

async function captureEvidence() {
  try {
    await captureExplanationEvidence(props.caseId, {
      sourceSystem: captureForm.value.sourceSystem,
      sourceReference: captureForm.value.sourceReference.trim(),
      contentSha256: captureForm.value.contentSha256.trim(),
    })
    ElMessage.success('材料已登记（登记 ≠ 已核验）；新材料进入待分派事实清单')
    captureForm.value.sourceReference = ''
    captureForm.value.contentSha256 = ''
    await reload()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '材料登记失败，请刷新后重试')
  }
}

const reviewBasisToken = ref('')

async function loadReviewBasis() {
  try {
    const { getExplanationReviewBasis } = await import('../api/client')
    const basis = await getExplanationReviewBasis(props.caseId)
    reviewBasisToken.value = basis.reviewBasisToken
    workspace.value = {
      ...(workspace.value as ExplanationWorkspaceView),
      canExclude: basis.canExclude,
      canConfirm: basis.canConfirm,
      confirmBlockers: basis.confirmBlockers,
      excludeBlockers: basis.excludeBlockers,
    }
    ElMessage.success(`复核依据已取号（epoch ${basis.caseFactsEpoch}）；令牌只在事实未变化时有效`)
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message ?? '复核依据获取失败')
  }
}

function questionFocus(policyCode: string | null): Record<string, string> {
  if (policyCode && policyQuestionFocus[policyCode]) return policyQuestionFocus[policyCode]
  return policyQuestionFocus.GOODS_SETTLED_V1
}

</script>

<template>
  <div class="card">
    <h3 class="card-title investigation-title">
      解释核验工作区（企业货款快进快出）
      <el-tag size="small" effect="plain" type="primary">契约 v2 · 逐预警单元</el-tag>
    </h3>
    <div v-if="loading" class="log-empty">正在加载工作区…</div>
    <template v-else-if="workspace">
      <p class="hint">
        本案 {{ explainCounts.total }} 条预警：{{ explainCounts.explained }} 条有解释、
        {{ explainCounts.suspicious }} 条有疑点、{{ explainCounts.unresolved }} 条未决
        （混合结论允许并存；假设汇总为{{ explainCounts.suspicious > 0 ? '存在支持怀疑的发现' : (explainCounts.unresolved > 0 ? '存在未决单元' : '全部解释成立') }}）
      </p>
      <el-alert v-if="workspace.generalBlockers.length" type="warning" :closable="false" show-icon
        title="当前阻断项">
        <template #default>{{ workspace.generalBlockers.join('；') }}</template>
      </el-alert>
      <div class="basis-bar">
        <el-button size="small" @click="loadReviewBasis">获取复核依据（取号）</el-button>
        <span class="muted">令牌绑定事实序号 {{ workspace.caseFactsEpoch }}；事实变化后需重新取号</span>
        <el-tag :type="workspace.canExclude ? 'success' : 'info'" size="small" effect="plain">可排除 {{ workspace.canExclude ? '是' : '否' }}</el-tag>
        <el-tag :type="workspace.canConfirm ? 'danger' : 'info'" size="small" effect="plain">可送确认可疑 {{ workspace.canConfirm ? '是' : '否' }}</el-tag>
      </div>

      <div v-for="unit in workspace.units" :key="unit.unitId" class="unit-card">
        <div class="unit-head">
          <strong>{{ unit.externalAlertId ?? `预警 #${unit.alertId}` }}</strong>
          <el-tag v-if="unit.currentOutcome" :type="outcomeTagType[unit.currentOutcome]" size="small" effect="dark">
            {{ outcomeText[unit.currentOutcome] }}
          </el-tag>
          <el-tag v-else type="info" size="small" effect="plain">未提交</el-tag>
          <el-tag v-if="unit.policyCode" size="small" effect="plain">{{ unit.policyCode }}</el-tag>
          <el-tag v-if="unit.followupRequired" type="warning" size="small" effect="plain">含跟进义务</el-tag>
        </div>
        <div v-if="unit.blockers.length" class="unit-blockers muted">{{ unit.blockers.join('；') }}</div>
        <div class="unit-actions">
          <el-button size="small" @click="openDraft(unit)">编辑草稿</el-button>
          <el-button size="small" type="primary" :loading="actionUnitId === unit.unitId"
            @click="saveDraft(unit)">保存草稿</el-button>
          <el-button size="small" type="success" :loading="actionUnitId === unit.unitId"
            @click="submitUnit(unit)">提交单元结论</el-button>
          <el-button v-if="unit.hasCurrentSubmission" size="small" type="warning" plain
            :loading="actionUnitId === unit.unitId" @click="amend(unit)">修订</el-button>
        </div>
        <div class="question-focus muted">
          <span v-for="code in QUESTION_CODES" :key="code" class="code-chip info">
            {{ code }} {{ questionFocus(unit.policyCode)[code] }}
          </span>
        </div>
      </div>

      <h4 class="sub-title">问题（差异/反证/缺口）</h4>
      <div v-for="issue in workspace.issues" :key="issue.issueId" class="issue-row">
        <el-tag :type="issue.severity === 'INTEGRITY_BLOCKER' ? 'danger' : (issue.severity === 'DECISION_CRITICAL' ? 'warning' : 'info')"
          size="small" effect="plain">{{ severityText[issue.severity] }}</el-tag>
        <span class="issue-desc">{{ issue.description }}</span>
        <el-tag size="small" effect="plain">{{ dispositionText[issue.disposition] }}</el-tag>
        <template v-if="issue.disposition === 'OPEN'">
          <el-button size="small" @click="disposeIssue(issue, 'RESOLVED_WITH_EVIDENCE')">凭证据解决</el-button>
          <el-button size="small" type="warning" plain
            @click="disposeIssue(issue, 'DISCLOSED_UNRESOLVED')">披露未解决</el-button>
          <el-button v-if="issue.severity !== 'INTEGRITY_BLOCKER'" size="small"
            @click="disposeIssue(issue, 'NOT_RELEVANT_WITH_REASON')">说明不相关</el-button>
        </template>
      </div>

      <h4 class="sub-title">材料登记（登记 ≠ 已核验）</h4>
      <div class="capture-bar">
        <el-select v-model="captureForm.sourceSystem" size="small" style="width: 180px">
          <el-option label="核心系统" value="CORE_BANKING" />
          <el-option label="物流平台" value="LOGISTICS_PLATFORM" />
          <el-option label="税务平台" value="TAX_PLATFORM" />
          <el-option label="KYC 平台" value="KYC_PLATFORM" />
        </el-select>
        <el-input v-model="captureForm.sourceReference" size="small" placeholder="不透明来源引用（如 TXN-DOC-001）"
          style="width: 240px" />
        <el-input v-model="captureForm.contentSha256" size="small" placeholder="实际内容 SHA-256"
          style="width: 320px" />
        <el-button size="small" type="primary" plain @click="captureEvidence">登记材料</el-button>
      </div>
      <div v-for="artifact in workspace.artifacts" :key="artifact.artifactVersionId" class="artifact-row muted">
        {{ artifact.artifactKey }} v{{ artifact.version }} · {{ artifact.sourceSystem }} ·
        完整性 {{ artifact.integrityStatus }} · 可用性 {{ artifact.availability }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.basis-bar { display: flex; align-items: center; gap: 10px; margin: 10px 0; flex-wrap: wrap; }
.unit-card { border: 1px solid var(--el-border-color-lighter, #ebeef5); border-radius: 8px; padding: 10px 12px; margin: 10px 0; }
.unit-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.unit-actions { display: flex; gap: 8px; margin: 8px 0 4px; flex-wrap: wrap; }
.unit-blockers { font-size: 12px; margin: 4px 0; }
.question-focus { display: flex; flex-wrap: wrap; gap: 6px; font-size: 12px; }
.sub-title { margin: 16px 0 8px; }
.issue-row { display: flex; align-items: center; gap: 8px; margin: 6px 0; flex-wrap: wrap; }
.issue-desc { flex: 1; min-width: 240px; font-size: 13px; }
.capture-bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.artifact-row { font-size: 12px; margin: 4px 0; }
.muted { color: var(--el-text-color-secondary, #909399); }
</style>
