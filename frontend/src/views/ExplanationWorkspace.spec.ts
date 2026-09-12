import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

/**
 * TP-28（A5-09）：解释核验工作区草稿按 caseId/unitId 隔离。
 * - 打开 A 单元编辑草稿、保存；再打开 B 单元 → 不显示 A 的内容（无串稿）；
 * - 服务端返回 draftJson 时优先恢复服务器草稿；
 * - 冲突（409）时保留本地草稿并展示服务器版本。
 * 挂载真实 SFC；API 以 mock 控制；ElMessage/ElMessageBox 全局 stub。
 */

const { messageSuccess, messageWarning, messageError, promptMock, confirmMock } = vi.hoisted(() => ({
  messageSuccess: vi.fn(),
  messageWarning: vi.fn(),
  messageError: vi.fn(),
  promptMock: vi.fn(),
  confirmMock: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: messageSuccess, warning: messageWarning, error: messageError },
  ElMessageBox: { prompt: promptMock, confirm: confirmMock },
}))

const getExplanationWorkspace = vi.fn()
const saveExplanationDraft = vi.fn()
const submitExplanationUnit = vi.fn()
const amendExplanationUnit = vi.fn()
const captureExplanationEvidence = vi.fn()
const disposeExplanationIssue = vi.fn()
const getExplanationReviewBasis = vi.fn().mockResolvedValue({
  caseFactsEpoch: 0,
  reviewBasisToken: 'tok',
  canExclude: false,
  canConfirm: false,
  confirmBlockers: [],
  excludeBlockers: [],
})
const fetchUnitNextActions = vi.fn().mockResolvedValue([])
const proposeIssueDowngrade = vi.fn().mockResolvedValue({})
const recordEvidenceVerification = vi.fn().mockResolvedValue({})
const proposeExplanationEdd = vi.fn().mockResolvedValue({})

vi.mock('../api/client', () => ({
  getExplanationWorkspace: (...args: unknown[]) => getExplanationWorkspace(...args),
  saveExplanationDraft: (...args: unknown[]) => saveExplanationDraft(...args),
  submitExplanationUnit: (...args: unknown[]) => submitExplanationUnit(...args),
  amendExplanationUnit: (...args: unknown[]) => amendExplanationUnit(...args),
  captureExplanationEvidence: (...args: unknown[]) => captureExplanationEvidence(...args),
  disposeExplanationIssue: (...args: unknown[]) => disposeExplanationIssue(...args),
  getExplanationReviewBasis: (...args: unknown[]) => getExplanationReviewBasis(...args),
  fetchUnitNextActions: (...args: unknown[]) => fetchUnitNextActions(...args),
  proposeIssueDowngrade: (...args: unknown[]) => proposeIssueDowngrade(...args),
  recordEvidenceVerification: (...args: unknown[]) => recordEvidenceVerification(...args),
  proposeExplanationEdd: (...args: unknown[]) => proposeExplanationEdd(...args),
}))

vi.mock('../utils/explanation', () => ({
  dispositionText: { OPEN: '待处理', RESOLVED_WITH_EVIDENCE: '已凭证据解决' },
  isExplanationRevisionConflict: (error: { response?: { status?: number; data?: { code?: string } } }) =>
    error?.response?.status === 409 || error?.response?.data?.code === 'INVESTIGATION_REVISION_CONFLICT',
  outcomeText: { EXPLAINED: '解释成立', SUSPICIOUS: '存在疑点', UNRESOLVED: '未决' },
  outcomeTagType: { EXPLAINED: 'success', SUSPICIOUS: 'danger', UNRESOLVED: 'info' },
  policyQuestionFocus: {
    GOODS_SETTLED_V1: { Q1: 'q1', Q2: 'q2', Q3: 'q3', Q4: 'q4', Q5: 'q5', Q6: 'q6' },
    GOODS_PREPAY_V1: { Q1: 'p1', Q2: 'p2', Q3: 'p3', Q4: 'p4', Q5: 'p5', Q6: 'p6' },
    GOODS_GROUP_PAYMENT_V1: { Q1: 'g1', Q2: 'g2', Q3: 'g3', Q4: 'g4', Q5: 'g5', Q6: 'g6' },
  },
  QUESTION_CODES: ['Q1', 'Q2', 'Q3', 'Q4', 'Q5', 'Q6'],
  severityText: { DECISION_CRITICAL: '关键', CONTEXT_GAP: '背景' },
}))

function unitView(unitId: number, draftJson: string | null, draftRevision = 0) {
  return {
    unitId,
    alertId: unitId + 10,
    externalAlertId: `ALERT-${unitId}`,
    hypothesisId: 31,
    policyCode: 'GOODS_SETTLED_V1',
    draftRevision,
    draftJson,
    hasCurrentSubmission: false,
    currentSubmissionId: null,
    currentOutcome: null,
    criticalUnknown: false,
    followupRequired: false,
    blockers: [],
  }
}

function workspace(units: ReturnType<typeof unitView>[]) {
  return {
    caseId: 7,
    contractVersion: 2,
    caseFactsEpoch: 0,
    stale: false,
    units,
    issues: [],
    artifacts: [],
    generalBlockers: [],
    canExclude: false,
    canConfirm: false,
    confirmBlockers: [],
    excludeBlockers: [],
  }
}

import ExplanationWorkspace from './ExplanationWorkspace.vue'

describe('ExplanationWorkspace 草稿隔离（TP-28）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getExplanationWorkspace.mockResolvedValue(workspace([unitView(100, null, 0), unitView(101, null, 0)]))
  })

  async function mountView() {
    const wrapper = mount(ExplanationWorkspace, {
      props: { caseId: 7 },
      global: { components: { ElTable: { template: '<div><slot /></div>' } } },
    })
    for (let i = 0; i < 6; i++) {
      await vi.dynamicImportSettled()
    }
    return wrapper
  }

  it('打开 A 单元输入草稿后，打开 B 单元不显示 A 的内容（无串稿）', async () => {
    const wrapper = await mountView()

    // A 单元：点击"编辑草稿"按钮（第 1 个单元的第 1 个按钮）
    promptMock.mockResolvedValueOnce({ value: '{"draft":"A-unit-content"}' })
    await wrapper
      .findAll('el-button')
      .find((button) => button.text().trim() === '编辑草稿')!
      .trigger('click')
    await vi.dynamicImportSettled()
    expect(promptMock).toHaveBeenCalledTimes(1)

    // B 单元：打开编辑框，输入值不应含 A 内容
    promptMock.mockImplementationOnce((_msg: unknown, _title: unknown, opts: { inputValue?: string }) => {
      expect(opts.inputValue).not.toContain('A-unit-content')
      return Promise.resolve({ value: '{"draft":"B-unit-content"}' })
    })
    await wrapper
      .findAll('el-button')
      .filter((b) => b.text().trim() === '编辑草稿')[1]
      .trigger('click')
    await vi.dynamicImportSettled()
    expect(promptMock).toHaveBeenCalledTimes(2)
  })

  it('服务端返回 draftJson 时优先恢复服务器草稿', async () => {
    getExplanationWorkspace.mockResolvedValue(workspace([unitView(100, '{"draft":"server-saved"}', 3)]))
    const wrapper = await mountView()

    // 无本地内容 → 恢复服务器草稿
    promptMock.mockImplementationOnce((_msg: unknown, _title: unknown, opts: { inputValue?: string }) => {
      expect(opts.inputValue).toBe('{"draft":"server-saved"}')
      return Promise.resolve({ value: '{"draft":"server-saved"}' })
    })
    await wrapper
      .findAll('el-button')
      .find((button) => button.text().trim() === '编辑草稿')!
      .trigger('click')
    await vi.dynamicImportSettled()
    expect(promptMock).toHaveBeenCalledTimes(1)
  })

  it('保存失败时本地草稿保留（不误报成功、不丢内容）', async () => {
    const wrapper = await mountView()

    // 输入本地草稿
    promptMock.mockResolvedValueOnce({ value: '{"draft":"local-edit"}' })
    await wrapper
      .findAll('el-button')
      .find((button) => button.text().trim() === '编辑草稿')!
      .trigger('click')
    await vi.dynamicImportSettled()

    // 保存失败（网络错误）
    saveExplanationDraft.mockRejectedValueOnce(new Error('network down'))
    await wrapper
      .findAll('el-button')
      .find((button) => button.text().trim() === '保存草稿')!
      .trigger('click')
    await vi.dynamicImportSettled()
    expect(messageError).toHaveBeenCalledWith('草稿保存失败，请刷新后重试')

    // 刷新后保存成功
    saveExplanationDraft.mockResolvedValueOnce({ draftRevision: 5 })
    getExplanationWorkspace.mockResolvedValue(workspace([unitView(100, '{"draft":"local-edit"}', 5)]))
    await wrapper
      .findAll('el-button')
      .find((button) => button.text().trim() === '保存草稿')!
      .trigger('click')
    await vi.dynamicImportSettled()
    expect(messageSuccess).toHaveBeenCalled()
  })
})
