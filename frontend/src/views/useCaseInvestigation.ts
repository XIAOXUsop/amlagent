import { ref, type ComputedRef, type Ref } from 'vue'
import {
  addInvestigationEvidence,
  getCaseInvestigation,
  getTransactionWindows,
  splitAlertToNewCase,
  updateAlertCoverage,
  updateInvestigationHypothesis,
  type CaseInvestigation,
  type CaseItem,
  type EvidenceStance,
  type InvestigationEvidenceType,
  type InvestigationHypothesis,
  type TransactionWindowView,
} from '../api/client'
import {
  coverageGap,
  coverageGapText,
  expectedConclusionFor,
  isInvestigationRevisionConflict,
  type CoverageGap,
} from '../utils/investigation'
import { apiErrorMessage, isDialogCancellation } from '../utils/api-error'
import {
  coverageConclusionText,
  evidenceStanceText,
  evidenceTypeText,
  formatAmount,
  hypothesisStatusText,
  investigationScenarioText,
  operationsPhaseText,
  operationsPriorityText,
  reviewReasonText,
} from './case-presenters'

interface InvestigationContext {
  caseId: () => number
  caseItem: Ref<CaseItem | null>
  investigation: Ref<CaseInvestigation | null>
  transactionWindows: Ref<TransactionWindowView | null>
  investigationLoadError: Ref<boolean>
  investigationActionId: Ref<number | null>
  evidenceDialogOpen: Ref<boolean>
  evidenceHypothesis: Ref<InvestigationHypothesis | null>
  evidenceType: Ref<InvestigationEvidenceType>
  evidenceStance: Ref<EvidenceStance>
  evidenceReference: Ref<string>
  evidenceSummary: Ref<string>
  evidenceSubmitting: Ref<boolean>
  canEditInvestigation: ComputedRef<boolean>
  connect: () => Promise<void>
  openCase: (caseId: number) => void
}

export function useCaseInvestigation(context: InvestigationContext) {
  const {
    caseId,
    caseItem,
    investigation,
    transactionWindows,
    investigationLoadError,
    investigationActionId,
    evidenceDialogOpen,
    evidenceHypothesis,
    evidenceType,
    evidenceStance,
    evidenceReference,
    evidenceSummary,
    evidenceSubmitting,
    canEditInvestigation,
    connect,
    openCase,
  } = context
  function coverageFor(alertId: number) {
    return investigation.value?.coverage.find((item) => item.alertId === alertId)
  }

  /** 覆盖与当前假设的差距：决定“形成结论”还是“重新确认”入口（详见 utils/investigation）。 */
  function coverageGapFor(alertId: number): CoverageGap | null {
    const coverage = coverageFor(alertId)
    return coverage ? coverageGap(coverage, hypothesisFor(coverage.hypothesisId)) : null
  }

  function hypothesisFor(id: number | null | undefined) {
    return investigation.value?.hypotheses.find((item) => item.id === id)
  }

  async function reloadInvestigation(targetCaseId = caseId()): Promise<boolean> {
    const [investigationView, windows] = await Promise.all([
      getCaseInvestigation(targetCaseId),
      getTransactionWindows(targetCaseId),
    ])
    if (caseId() !== targetCaseId) return false
    investigation.value = investigationView
    transactionWindows.value = windows
    investigationLoadError.value = false
    // 重新加载成功后事实恢复新鲜；stale 标记只在“写入成功但刷新失败”时置位
    investigationStale.value = false
    return true
  }

  /**
   * A4-01：写入成功但刷新失败后的事实恢复入口。
   * 只重新加载，不重放任何写入；成功后解除 stale 限制，失败保持限制并提示。
   */
  async function reloadInvestigationFacts() {
    try {
      await reloadInvestigation()
    } catch {
      ElMessage.error('重新加载调查事实失败，请检查网络后重试；在新事实加载前不能继续编辑')
    }
  }

  function openInvestigationEvidence(hypothesis: InvestigationHypothesis) {
    evidenceHypothesis.value = hypothesis
    evidenceType.value = hypothesis.requiredEvidenceTypes[0] ?? 'TRANSACTION'
    evidenceStance.value = 'SUPPORTS'
    evidenceReference.value = caseItem.value?.snapshotId
      ? `SNAPSHOT-${caseItem.value.snapshotId}`
      : `CASE-${caseId()}-EVIDENCE`
    evidenceSummary.value = ''
    evidenceDialogOpen.value = true
  }

  async function submitInvestigationEvidence() {
    const actionCaseId = caseId()
    const hypothesis = evidenceHypothesis.value
    if (!hypothesis) return
    if (
      !/^[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}$/.test(evidenceReference.value.trim()) ||
      evidenceSummary.value.trim().length < 10
    ) {
      ElMessage.warning('请填写有效证据引用和至少 10 个字符的调查摘要')
      return
    }
    evidenceSubmitting.value = true
    try {
      await addInvestigationEvidence(actionCaseId, hypothesis.id, {
        evidenceType: evidenceType.value,
        evidenceReference: evidenceReference.value.trim(),
        stance: evidenceStance.value,
        findingSummary: evidenceSummary.value.trim(),
      })
      if (!(await reloadInvestigation(actionCaseId))) return
      evidenceDialogOpen.value = false
      ElMessage.success('调查证据已关联到假设')
    } catch (error: unknown) {
      ElMessage.error(apiErrorMessage(error, '证据关联失败，请刷新后重试'))
    } finally {
      evidenceSubmitting.value = false
    }
  }

  /**
   * W1/A3-02：按假设隔离的判断草稿（页面内存，不持久化）。
   * 记录目标结论与依据；保存成功或用户明确放弃时清理；任何失败（冲突刷新失败、网络错误）
   * 均保留，重新打开时恢复，禁止在失败分支误提示“已保存”。
   */
  const hypothesisDrafts = ref<Record<number, { conclusion: 'CONFIRMED' | 'REJECTED'; rationale: string }>>({})

  /** A4-01：写入已成功但调查事实刷新失败时置位——旧事实不能继续当作当前事实编辑或重复提交。 */
  const investigationStale = ref(false)

  /**
   * 确认/排除/修改/重申调查假设：与覆盖编辑同一冲突恢复原则。
   * 冲突时保留用户输入，刷新最新假设状态与依据，由用户明确确认后以新版本提交；
   * 成功提示区分幂等重申（版本不变、覆盖未失效）与实际改判。
   */
  async function decideInvestigationHypothesis(hypothesis: InvestigationHypothesis, status: 'CONFIRMED' | 'REJECTED') {
    const actionCaseId = caseId()
    if (investigationStale.value) {
      // A4-01：写入已成功但事实未刷新，旧版本/旧依据不能作为当前事实再次提交
      ElMessage.warning('上次保存成功后未加载到最新调查事实；请先点击“重新加载调查事实”核对新依据后再继续编辑')
      return
    }
    investigationActionId.value = hypothesis.id
    const draft = hypothesisDrafts.value[hypothesis.id]
    const restoringDraft = !!draft && draft.conclusion === status
    let current = hypothesis
    let expectedRevision = hypothesis.revision
    let analysis = restoringDraft ? draft.rationale : ''
    let conflictRound = false
    try {
      for (;;) {
        const redecide = current.status !== 'OPEN'
        const reaffirm = redecide && status === current.status
        const title = reaffirm
          ? '重申调查假设判断'
          : redecide
            ? '修改调查假设判断'
            : status === 'CONFIRMED'
              ? '确认调查假设'
              : '排除调查假设'
        const restored = restoringDraft
          ? `已恢复你上次未保存的输入（目标结论：${status === 'CONFIRMED' ? '确认' : '排除'}）。\n\n`
          : ''
        const message = conflictRound
          ? `调查假设已被他人更新（当前状态：${hypothesisStatusName(current.status)}；当前版本 ${current.revision}）。\n` +
            `最新判断依据：${current.rationale ?? '（未填写）'}。\n` +
            `你已输入的判断依据已保留，请核对后再次确认。`
          : restored +
            (reaffirm
              ? '重申当前判断：保持原结论，仅重新确认依据；若依据与当前完全相同，后端幂等返回且不会递增版本。'
              : redecide
                ? '修改判断将递增假设版本，并使引用该假设的预警覆盖结论失效（需重新确认）。请填写新的判断依据。'
                : status === 'CONFIRMED'
                  ? '说明支持证据如何证明该风险假设。'
                  : '说明哪些反向证据足以排除该风险假设。')
        let value: string
        try {
          const prompt = await ElMessageBox.prompt(message, title, {
            inputType: 'textarea',
            inputValue: analysis || (redecide && !restoringDraft ? (current.rationale ?? '') : ''),
            inputValidator: (text: string) => text?.trim().length >= 10 || '判断依据至少 10 个字符',
          })
          value = prompt.value.trim()
        } catch (dialogError) {
          if (dialogError === 'cancel' || dialogError === 'close') {
            // 用户明确放弃编辑：清理草稿
            delete hypothesisDrafts.value[hypothesis.id]
          }
          return
        }
        if (caseId() !== actionCaseId) return
        analysis = value
        // 发请求前先把草稿写到对话框外的页面级状态（按案件与假设隔离）
        hypothesisDrafts.value[current.id] = { conclusion: status, rationale: analysis }
        try {
          const result = await updateInvestigationHypothesis(actionCaseId, current.id, {
            expectedRevision,
            status,
            rationale: analysis,
          })
          // 写入明确成功：清理草稿并给出真实的保存结果；刷新失败不再当作提交失败。
          delete hypothesisDrafts.value[current.id]
          if (result.revision === expectedRevision) {
            ElMessage.success('判断依据未变化（幂等确认）：未递增版本，覆盖结论未失效')
          } else if (redecide && reaffirm) {
            ElMessage.success('判断依据已更新并递增版本；引用该假设的覆盖结论已重置为待确认')
          } else {
            ElMessage.success(status === 'CONFIRMED' ? '调查假设已确认' : '调查假设已排除')
          }
          try {
            if (!(await reloadInvestigation(actionCaseId))) return
          } catch {
            // A4-01：已保存，但事实加载失败 —— 不声称更新失败、不承诺未保存草稿，限制后续编辑直至重新加载
            investigationStale.value = true
            ElMessage.warning(
              '已保存，但最新调查事实加载失败；请点击“重新加载调查事实”核对新依据后再继续编辑，不要基于旧依据重复提交',
            )
            return
          }
          return
        } catch (submitError: unknown) {
          if (!isInvestigationRevisionConflict(submitError)) {
            // 普通失败：草稿保留，可重新打开恢复；不误提示已保存
            ElMessage.error(
              apiErrorMessage(submitError, '假设更新失败，请检查必需证据是否完整（你输入的判断依据已保留）'),
            )
            return
          }
          conflictRound = true
          try {
            if (!(await reloadInvestigation(actionCaseId))) return
          } catch {
            ElMessage.error('刷新调查事实失败，请稍后重新执行该操作；你输入的判断依据已保留')
            return
          }
          const refreshed = investigation.value?.hypotheses.find((item) => item.id === current.id)
          if (!refreshed) {
            ElMessage.warning('该调查假设已不存在（可能随预警拆分移动），本次提交已取消（你输入的判断依据已保留）')
            return
          }
          if (refreshed.status === 'OPEN') {
            ElMessage.warning('该调查假设已被重置为未决，请重新执行确认或排除操作（你输入的判断依据已保留）')
            return
          }
          current = refreshed
          expectedRevision = refreshed.revision
          continue
        }
      }
    } finally {
      investigationActionId.value = null
    }
  }

  /** W1：按预警隔离的覆盖分析草稿（页面内存，不持久化）；仅在保存成功或用户明确放弃时清理。 */
  const coverageDrafts = ref<Record<number, string>>({})

  /** 最近一次冲突错误（用于区分“假设被改判”与“覆盖被他人更新”的提示）。 */
  let lastConflictError: unknown = null

  interface CoverageEditContext {
    coverage: { revision: number; analysisSummary: string | null }
    hypothesis: { id: number; revision: number; status: string; rationale: string | null }
    conclusion: 'SUSPICIOUS' | 'EXPLAINED'
    gap: CoverageGap | null
  }

  /** 提交前重新解析当前事实；返回 blocked 时说明原因，不提交无效 ID 或错误结论。 */
  function resolveCoverageEditContext(alertId: number): { context: CoverageEditContext } | { blocked: string } {
    if (investigationStale.value) {
      return { blocked: '上次保存成功后未加载到最新调查事实；请先点击“重新加载调查事实”后再继续编辑' }
    }
    if (!canEditInvestigation.value) {
      return { blocked: '案件当前为只读状态，不能提交覆盖结论' }
    }
    const coverage = coverageFor(alertId)
    if (!coverage) {
      return { blocked: '该预警的覆盖结论不存在（可能已被拆分到其它案件），请刷新后重试' }
    }
    const hypothesis = hypothesisFor(coverage.hypothesisId)
    if (!hypothesis) {
      return { blocked: '该预警关联的调查假设不存在，请刷新后重试' }
    }
    if (hypothesis.status === 'OPEN') {
      return { blocked: '该预警关联的调查假设仍是未决状态，请先完成确认或排除' }
    }
    const conclusion = expectedConclusionFor(hypothesis)
    if (!conclusion) {
      return { blocked: '关联调查假设状态无法推导覆盖结论，请刷新后重试' }
    }
    return { context: { coverage, hypothesis, conclusion, gap: coverageGapFor(alertId) } }
  }

  function conflictTypeText(error: unknown): string {
    const type = (error as { response?: { data?: { conflict?: { type?: string } } } })?.response?.data?.conflict?.type
    return type === 'COVERAGE' ? '该预警的覆盖结论已被他人更新' : '判断依据的假设已被他人改判'
  }

  function coverageConclusionLabel(conclusion: 'SUSPICIOUS' | 'EXPLAINED'): string {
    return conclusion === 'SUSPICIOUS' ? '确认可疑' : '合理解释'
  }

  /**
   * 统一的覆盖结论编辑入口（形成结论 / 重新确认共用同一状态机）。
   * <ul>
   *   <li>草稿按预警隔离存于页面内存；发请求前先写入，仅保存成功或用户明确放弃时清理；</li>
   *   <li>版本冲突（409 + INVESTIGATION_REVISION_CONFLICT 协议）时刷新调查事实、保留草稿，
   *       展示最新假设依据与将提交的结论，用户明确确认后才以新版本提交；</li>
   *   <li>不把任意 409/412 当作冲突自动刷新重试；刷新失败/二次冲突均保留草稿并给出重试提示。</li>
   * </ul>
   */
  async function editAlertCoverage(alertId: number, mode: 'CONCLUDE' | 'RECONFIRM') {
    const actionCaseId = caseId()
    const opened = resolveCoverageEditContext(alertId)
    if ('blocked' in opened) {
      ElMessage.warning(opened.blocked)
      return
    }
    let context = opened.context
    let analysis =
      mode === 'RECONFIRM'
        ? (coverageDrafts.value[alertId] ?? context.coverage.analysisSummary ?? '')
        : (coverageDrafts.value[alertId] ?? '')
    let conflictRound = false
    try {
      for (;;) {
        const isFirstRound = !conflictRound
        const title = isFirstRound
          ? mode === 'RECONFIRM'
            ? '重新确认覆盖结论'
            : context.conclusion === 'SUSPICIOUS'
              ? '形成可疑覆盖结论'
              : '形成合理解释结论'
          : '重新确认覆盖结论（已刷新）'
        const message = isFirstRound
          ? mode === 'RECONFIRM'
            ? `${coverageGapText(context.gap ?? 'PENDING')}。\n\n当前假设依据：${context.hypothesis.rationale ?? '（未填写）'}`
            : '逐项说明该调查假设和证据如何覆盖本条预警，不可只填写结论。'
          : `${conflictTypeText(lastConflictError)}。\n\n最新假设状态：${hypothesisStatusName(context.hypothesis.status)}；` +
            `最新判断依据：${context.hypothesis.rationale ?? '（未填写）'}。\n` +
            `确认后将提交的覆盖结论：${coverageConclusionLabel(context.conclusion)}（以当前假设状态为准）。\n` +
            `你已输入的分析已保留，请核对后再次确认。`
        let dialogValue: string
        try {
          const { value } = await ElMessageBox.prompt(message, title, {
            inputType: 'textarea',
            inputValue: analysis,
            inputValidator: (text: string) => text?.trim().length >= 10 || '覆盖分析至少 10 个字符',
          })
          dialogValue = value.trim()
        } catch (dialogError) {
          if (dialogError === 'cancel' || dialogError === 'close') {
            // 用户明确放弃编辑：清理草稿
            delete coverageDrafts.value[alertId]
          }
          return
        }
        if (caseId() !== actionCaseId) return
        analysis = dialogValue
        // 发请求前先把草稿写到对话框外的响应式状态
        coverageDrafts.value[alertId] = analysis
        investigationActionId.value = alertId
        try {
          await updateAlertCoverage(actionCaseId, alertId, {
            expectedRevision: context.coverage.revision,
            hypothesisId: context.hypothesis.id,
            expectedHypothesisRevision: context.hypothesis.revision,
            conclusion: context.conclusion,
            analysisSummary: analysis,
          })
          // 写入明确成功：清理草稿并给出真实的保存结果；刷新失败不再当作提交失败。
          delete coverageDrafts.value[alertId]
          ElMessage.success(
            isFirstRound && mode === 'RECONFIRM'
              ? '覆盖结论已重新确认（版本绑定已补齐）'
              : isFirstRound
                ? '预警覆盖结论已保存'
                : '覆盖结论已基于最新依据保存',
          )
          try {
            if (!(await reloadInvestigation(actionCaseId))) return
          } catch {
            // A4-01：已保存，但事实加载失败 —— 不声称更新失败、不承诺未保存草稿，限制后续编辑直至重新加载
            investigationStale.value = true
            ElMessage.warning(
              '已保存，但最新调查事实加载失败；请点击“重新加载调查事实”核对新结论后再继续编辑，不要基于旧事实重复提交',
            )
            return
          }
          return
        } catch (submitError: unknown) {
          if (isInvestigationRevisionConflict(submitError)) {
            // 版本冲突：保留草稿，刷新后由用户明确确认再提交（不自动重放）
            lastConflictError = submitError
            conflictRound = true
            try {
              if (!(await reloadInvestigation(actionCaseId))) return
            } catch {
              ElMessage.error('刷新调查事实失败；已保留你输入的分析，请稍后重新打开该预警的确认入口')
              return
            }
            const refreshed = resolveCoverageEditContext(alertId)
            if ('blocked' in refreshed) {
              ElMessage.warning(`无法继续提交：${refreshed.blocked}（已保留你输入的分析）`)
              return
            }
            context = refreshed.context
            continue
          }
          ElMessage.error(apiErrorMessage(submitError, '覆盖结论保存失败，请刷新后重试（已保留你输入的分析）'))
          return
        }
      }
    } finally {
      investigationActionId.value = null
    }
  }

  function hypothesisStatusName(status: string): string {
    return status === 'CONFIRMED' ? '已确认' : status === 'REJECTED' ? '已排除' : '未决'
  }

  /**
   * 重新确认存量/过期覆盖（A1）：与“形成结论”共用同一编辑状态机。
   * 不要求反转调查判断；结论以关联假设当前状态为准。
   */
  async function reconfirmAlertCoverage(alertId: number) {
    await editAlertCoverage(alertId, 'RECONFIRM')
  }

  async function splitCaseAlert(alert: { id: number; revision: number }) {
    const actionCaseId = caseId()
    try {
      const { value } = await ElMessageBox.prompt(
        '拆分后该预警会形成独立案件并重新调查，请说明其与当前案件不应合并的原因。',
        '拆分预警',
        {
          inputType: 'textarea',
          inputValidator: (text: string) => text?.trim().length >= 10 || '拆分原因至少 10 个字符',
        },
      )
      if (caseId() !== actionCaseId) return
      investigationActionId.value = alert.id
      const created = await splitAlertToNewCase(alert.id, alert.revision, value.trim(), { autoProcess: false })
      if (caseId() !== actionCaseId) return
      if (caseItem.value?.status === 'FAILED') {
        ElMessage.success(`预警已拆分为案件 #${created.id}；原案件可在确认剩余预警后执行人工重试继续调查`)
        await connect()
      } else {
        ElMessage.success(`预警已拆分为案件 #${created.id}（暂未开始调查，可继续调整边界后开始调查）`)
        openCase(created.id)
      }
    } catch (error: unknown) {
      if (isDialogCancellation(error)) return
      ElMessage.error(apiErrorMessage(error, '预警拆分失败，请刷新后重试'))
    } finally {
      investigationActionId.value = null
    }
  }

  function resetInvestigationUi() {
    evidenceDialogOpen.value = false
    evidenceHypothesis.value = null
    evidenceSubmitting.value = false
    investigationActionId.value = null
    hypothesisDrafts.value = {}
    coverageDrafts.value = {}
    investigationStale.value = false
  }

  return {
    investigationScenarioText,
    evidenceTypeText,
    evidenceStanceText,
    hypothesisStatusText,
    coverageConclusionText,
    operationsPriorityText,
    operationsPhaseText,
    coverageFor,
    coverageGapFor,
    hypothesisFor,
    reloadInvestigation,
    reloadInvestigationFacts,
    openInvestigationEvidence,
    submitInvestigationEvidence,
    hypothesisDrafts,
    investigationStale,
    decideInvestigationHypothesis,
    coverageDrafts,
    lastConflictError,
    resolveCoverageEditContext,
    conflictTypeText,
    coverageConclusionLabel,
    editAlertCoverage,
    hypothesisStatusName,
    reconfirmAlertCoverage,
    splitCaseAlert,
    resetInvestigationUi,
    formatAmount,
    reviewReasonText,
    coverageGapText,
  }
}
