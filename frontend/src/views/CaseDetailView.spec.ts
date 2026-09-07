import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { cloneVNode, defineComponent, h, ref } from 'vue'
import CaseDetailView from './CaseDetailView.vue'

/**
 * W1/V2-04~07 页面交互回归：
 * - 实际点击“形成结论”/“重新确认”按钮并走完整冲突恢复状态机；
 * - 冲突（409 + INVESTIGATION_REVISION_CONFLICT）后刷新事实、逐字保留草稿、
 *   由用户明确确认后才以刷新后的版本重新提交；
 * - 不把任意 409/412 当作冲突；刷新失败/刷新后不可提交时保留草稿并停止。
 * 通过 mock API 模块挂载真实 SFC + el-table stub；ElMessage/ElMessageBox 以全局 stub 控制。
 */

const messageSuccess = vi.fn()
const messageWarning = vi.fn()
const messageError = vi.fn()
const promptMock = vi.fn()

;(globalThis as unknown as Record<string, unknown>).ElMessage = {
  success: messageSuccess, warning: messageWarning, error: messageError,
}
;(globalThis as unknown as Record<string, unknown>).ElMessageBox = { prompt: promptMock }

vi.mock('../api/client', () => ({
  addInvestigationEvidence: vi.fn().mockResolvedValue({}),
  fmtDateTime: vi.fn(() => '2026-09-05 12:00'),
  getCaseDossier: vi.fn().mockResolvedValue({ contentHash: 'x' }),
  getCase: vi.fn(),
  getCaseInvestigation: vi.fn(),
  getCaseOperations: vi.fn().mockResolvedValue(null),
  getSuspiciousTransactionReport: vi.fn().mockResolvedValue(null),
  getTransactionWindows: vi.fn().mockResolvedValue({
    asOfTime: '', sourceSystem: 'MOCK', sourceVersion: 'v1', windows: [],
  }),
  listCaseReviews: vi.fn().mockResolvedValue([]),
  listEnhancedDueDiligence: vi.fn().mockResolvedValue([]),
  listLogs: vi.fn().mockResolvedValue([]),
  listToolTraces: vi.fn().mockResolvedValue([]),
  parseReport: vi.fn(() => null),
  processCase: vi.fn(),
  retryCase: vi.fn(),
  returnSuspiciousReport: vi.fn(),
  splitAlertToNewCase: vi.fn(),
  submitEnhancedDueDiligence: vi.fn(),
  updateAlertCoverage: vi.fn(),
  updateInvestigationHypothesis: vi.fn(),
  reviewSanctionCandidate: vi.fn(),
  screenSanctions: vi.fn(),
  subscribeCase: vi.fn(() => () => undefined),
}))

vi.mock('../auth', () => ({
  currentUser: ref({ username: 'analyst', role: 'ANALYST' }),
  authReady: Promise.resolve(),
  markAuthReady: () => undefined,
}))

import {
  getCase, getCaseInvestigation, updateAlertCoverage, updateInvestigationHypothesis,
} from '../api/client'

function caseItem(status: string) {
  return {
    id: 7, customerId: 'C001', customerName: '客户', alertRule: '常规监测', status,
    riskLevel: '低风险', rawRiskLevel: '低风险', reportJson: null, summary: null,
    reportSource: 'AGENT', snapshotId: 'case-7-v1', modelProvider: 'mock', modelName: 'mock',
    modelFallback: false, executionVersion: 1, reviewRevision: 0, investigationContractVersion: 1,
    reviewDisposition: null, reviewReasonCode: null, reviewedAt: null, retryCount: 0,
    failureCode: status === 'FAILED' ? 'NON_RETRYABLE' : null,
    failureMessage: status === 'FAILED' ? '容量上限' : null,
    createdAt: '', updatedAt: '',
  } as never
}

function hypothesis(id: number, revision: number, status: string, rationale = '当前判断依据') {
  return {
    id, caseId: 7, scenarioCode: 'STRUCTURING', hypothesisCode: `H-${id}`, title: '拆分假设',
    investigationQuestion: '是否成立？', requiredEvidenceTypes: [], status,
    rationale, revision, createdBy: 'analyst', updatedBy: 'analyst',
    createdAt: '', updatedAt: '', evidence: [],
  }
}

function coverage(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, alertId: 11, caseId: 7, hypothesisId: 31, hypothesisRevision: null,
    conclusion: 'PENDING', analysisSummary: null, revision: 1,
    updatedBy: 'analyst', updatedAt: '', ...overrides,
  }
}

function investigationView(cov: Record<string, unknown>, hyp = hypothesis(31, 3, 'CONFIRMED'),
  extraAlerts = 0) {
  const alerts = [{
    id: 11, externalAlertId: 'ALERT-A', customerId: 'C001', ruleCode: 'RULE-001',
    scenarioCode: 'STRUCTURING', hitReason: '拆分现金交易', occurredAt: '', status: 'LINKED',
    caseId: 7, revision: 0, resolutionReason: '', createdAt: '', updatedAt: '',
  }]
  for (let i = 0; i < extraAlerts; i++) {
    alerts.push({ ...alerts[0], id: 20 + i, externalAlertId: `ALERT-X${i}` })
  }
  return {
    contractVersion: 1,
    alerts,
    hypotheses: [hyp],
    coverage: [cov],
    readyForFinalReview: false,
    generalBlockers: [],
    confirmSuspiciousBlockers: [],
    excludeFalsePositiveBlockers: [],
  } as never
}

/** 冲突后乙真实改判的事实：假设 3→4，覆盖重置 PENDING 且 revision 1→2。 */
function redecidedInvestigationView() {
  return investigationView(coverage({ conclusion: 'PENDING', revision: 2, analysisSummary: null }),
    hypothesis(31, 4, 'CONFIRMED'))
}

function conflictError(type: 'HYPOTHESIS' | 'COVERAGE', currentVersion: number) {
  return {
    response: {
      status: 409,
      data: {
        code: 'INVESTIGATION_REVISION_CONFLICT',
        message: '调查记录已更新，请刷新后重新确认',
        conflict: { type, id: 11, currentVersion },
      },
    },
  }
}

// 测试环境刻意不引入 Element Plus（其按需样式导入在 node ESM 下无法解析）。
// el-table/el-table-column 提供最小 stub：遍历 data 并把 row 注入列的默认插槽。
const ElTableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    return () => h('div', { class: 'stub-table' }, (props.data as unknown[]).map((row) =>
      h('div', { class: 'stub-row' },
        ((slots.default?.() ?? []) as ReturnType<typeof h>[]).map((column) =>
          cloneVNode(column, { row } as never)),
      )))
  },
})

const ElTableColumnStub = defineComponent({
  props: { row: { type: Object, default: undefined } },
  setup(props, { slots }) {
    return () => (slots.default ? slots.default({ row: props.row }) : null)
  },
})

async function mountDetail(status: string, cov: Record<string, unknown>,
  hyp = hypothesis(31, 3, 'CONFIRMED'), extraAlerts = 0) {
  vi.mocked(getCase).mockResolvedValue(caseItem(status))
  vi.mocked(getCaseInvestigation).mockResolvedValue(investigationView(cov, hyp, extraAlerts))
  const wrapper = mount(CaseDetailView, {
    props: { caseId: 7 },
    global: { components: { ElTable: ElTableStub, ElTableColumn: ElTableColumnStub } },
  })
  for (let i = 0; i < 6; i++) {
    await flushPromises()
  }
  return wrapper
}

async function settle(times = 10) {
  for (let i = 0; i < times; i++) {
    await flushPromises()
  }
}

function buttons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('el-button').map((b) => b.text().trim())
}

async function clickButton(wrapper: ReturnType<typeof mount>, text: string) {
  const target = wrapper.findAll('el-button').find((b) => b.text().trim() === text)
  expect(target, `应存在按钮：${text}`).toBeTruthy()
  await target!.trigger('click')
  await settle()
  return target!
}

const legacyDecidedCoverage = coverage({
  conclusion: 'SUSPICIOUS', analysisSummary: '原覆盖分析',
})

const pendingCoverage = coverage({})

beforeEach(() => {
  vi.clearAllMocks()
  promptMock.mockReset()
  vi.mocked(updateAlertCoverage).mockReset()
  vi.mocked(updateInvestigationHypothesis).mockReset()
  vi.mocked(getCase).mockReset()
  vi.mocked(getCaseInvestigation).mockReset()
})

describe('CaseDetailView 存量覆盖重新确认入口（A1，保留）', () => {
  it('HOLD 案件中 NULL 版本绑定的已决覆盖出现“重新确认”，不出现“形成结论”', async () => {
    const wrapper = await mountDetail('HOLD', legacyDecidedCoverage)
    const text = wrapper.html()
    expect(buttons(wrapper)).toContain('重新确认')
    expect(text).toContain('需重新确认')
    expect(buttons(wrapper)).not.toContain('形成结论')
    expect(text).toContain('缺少假设版本绑定')
    wrapper.unmount()
  })

  it('PENDING 覆盖仍显示“形成结论”而非重新确认', async () => {
    const wrapper = await mountDetail('HOLD', pendingCoverage)
    expect(buttons(wrapper)).toContain('形成结论')
    expect(buttons(wrapper)).not.toContain('重新确认')
    wrapper.unmount()
  })

  it('DONE 案件不提供任何调查编辑入口（只读）', async () => {
    const wrapper = await mountDetail('DONE', legacyDecidedCoverage)
    expect(buttons(wrapper)).not.toContain('形成结论')
    expect(buttons(wrapper)).not.toContain('重新确认')
    expect(buttons(wrapper)).not.toContain('拆分')
    wrapper.unmount()
  })

  it('FAILED 容量案件保留“拆分”入口用于受控恢复（A2）', async () => {
    const wrapper = await mountDetail('FAILED', pendingCoverage, hypothesis(31, 3, 'CONFIRMED'), 1)
    expect(buttons(wrapper)).toContain('拆分')
    wrapper.unmount()
  })
})

describe('W1 覆盖编辑冲突恢复状态机', () => {
  /** V2-04：形成结论后发生改判冲突 → 刷新、草稿逐字保留、用户确认后以新版本提交。 */
  it('形成结论遇到改判冲突时保留草稿并以刷新后的版本重新提交', async () => {
    const wrapper = await mountDetail('HOLD', pendingCoverage)
    vi.mocked(updateAlertCoverage)
      .mockRejectedValueOnce(conflictError('HYPOTHESIS', 4))
      .mockResolvedValueOnce({} as never)
    vi.mocked(getCaseInvestigation).mockResolvedValueOnce(redecidedInvestigationView())
    promptMock.mockResolvedValueOnce({ value: '甲基于旧页面输入的第一版分析' })
    promptMock.mockResolvedValueOnce({ value: '甲基于最新依据重新编辑的分析' })

    await clickButton(wrapper, '形成结论')

    expect(promptMock).toHaveBeenCalledTimes(2)
    // 第二个对话框逐字保留第一轮输入的草稿
    expect(promptMock.mock.calls[1][2].inputValue).toBe('甲基于旧页面输入的第一版分析')
    // 第一次提交基于旧版本；第二次提交使用刷新后的事实（覆盖 rev 2、假设 rev 4）
    expect(updateAlertCoverage).toHaveBeenCalledTimes(2)
    expect(updateAlertCoverage).toHaveBeenNthCalledWith(1, 7, 11, expect.objectContaining({
      expectedRevision: 1, expectedHypothesisRevision: 3,
    }))
    expect(updateAlertCoverage).toHaveBeenNthCalledWith(2, 7, 11, {
      expectedRevision: 2, hypothesisId: 31, expectedHypothesisRevision: 4,
      conclusion: 'SUSPICIOUS', analysisSummary: '甲基于最新依据重新编辑的分析',
    })
    expect(messageSuccess).toHaveBeenCalled()
    wrapper.unmount()
  })

  /** V2-05：重新确认路径编辑后冲突 → 保留新编辑内容，不退回数据库旧分析。 */
  it('重新确认遇到冲突时保留用户的新编辑而非数据库旧分析', async () => {
    const wrapper = await mountDetail('HOLD', legacyDecidedCoverage)
    vi.mocked(updateAlertCoverage)
      .mockRejectedValueOnce(conflictError('COVERAGE', 2))
      .mockResolvedValueOnce({} as never)
    vi.mocked(getCaseInvestigation).mockResolvedValueOnce(redecidedInvestigationView())
    promptMock.mockResolvedValueOnce({ value: '甲重新编辑后的覆盖分析内容' })
    promptMock.mockResolvedValueOnce({ value: '甲重新编辑后的覆盖分析内容' })

    await clickButton(wrapper, '重新确认')

    expect(promptMock).toHaveBeenCalledTimes(2)
    // 首轮预填数据库旧分析；冲突后第二轮保留用户刚编辑的内容
    expect(promptMock.mock.calls[0][2].inputValue).toBe('原覆盖分析')
    expect(promptMock.mock.calls[1][2].inputValue).toBe('甲重新编辑后的覆盖分析内容')
    expect(updateAlertCoverage).toHaveBeenNthCalledWith(2, 7, 11, expect.objectContaining({
      expectedRevision: 2, expectedHypothesisRevision: 4,
      analysisSummary: '甲重新编辑后的覆盖分析内容',
    }))
    wrapper.unmount()
  })

  /** V2-06a：连续两次冲突 → 无未处理异常，每次刷新后仍需用户明确确认。 */
  it('连续两次冲突后仍能完成保存且不提交错误版本', async () => {
    const wrapper = await mountDetail('HOLD', pendingCoverage)
    vi.mocked(updateAlertCoverage)
      .mockRejectedValueOnce(conflictError('HYPOTHESIS', 4))
      .mockRejectedValueOnce(conflictError('HYPOTHESIS', 5))
      .mockResolvedValueOnce({} as never)
    vi.mocked(getCaseInvestigation)
      .mockResolvedValueOnce(redecidedInvestigationView())
      .mockResolvedValueOnce(
        investigationView(coverage({ conclusion: 'PENDING', revision: 3 }),
          hypothesis(31, 5, 'CONFIRMED')))
    promptMock.mockResolvedValue({ value: '连续冲突场景下的分析草稿内容' })

    await clickButton(wrapper, '形成结论')

    expect(promptMock).toHaveBeenCalledTimes(3)
    expect(updateAlertCoverage).toHaveBeenCalledTimes(3)
    expect(updateAlertCoverage).toHaveBeenNthCalledWith(3, 7, 11, expect.objectContaining({
      expectedRevision: 3, expectedHypothesisRevision: 5,
    }))
    expect(messageError).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  /** V2-06b：刷新失败 → 保留草稿、给出重试提示；重新点击时草稿仍在。 */
  it('冲突后刷新失败时保留草稿并允许用户重新打开继续编辑', async () => {
    const wrapper = await mountDetail('HOLD', legacyDecidedCoverage)
    vi.mocked(updateAlertCoverage).mockRejectedValueOnce(conflictError('HYPOTHESIS', 4))
    vi.mocked(getCaseInvestigation).mockRejectedValueOnce(new Error('network down'))
    promptMock.mockResolvedValue({ value: '网络中断前输入的分析草稿内容' })

    await clickButton(wrapper, '重新确认')

    expect(updateAlertCoverage).toHaveBeenCalledTimes(1)
    expect(messageError).toHaveBeenCalled()
    // 草稿未丢：再次点击时对话框仍预填用户输入
    await clickButton(wrapper, '重新确认')
    expect(promptMock).toHaveBeenCalledTimes(2)
    expect(promptMock.mock.calls[1][2].inputValue).toBe('网络中断前输入的分析草稿内容')
    wrapper.unmount()
  })

  /** V2-06c：刷新后假设变为未决 → 停止提交并说明原因，草稿保留。 */
  it('刷新后假设未决时停止提交并说明原因', async () => {
    const wrapper = await mountDetail('HOLD', pendingCoverage)
    vi.mocked(updateAlertCoverage).mockRejectedValueOnce(conflictError('HYPOTHESIS', 4))
    vi.mocked(getCaseInvestigation).mockResolvedValueOnce(
      investigationView(coverage({ conclusion: 'PENDING', revision: 2 }),
        hypothesis(31, 4, 'OPEN')))
    promptMock.mockResolvedValueOnce({ value: '假设被重置前的分析草稿内容' })

    await clickButton(wrapper, '形成结论')

    expect(updateAlertCoverage).toHaveBeenCalledTimes(1)
    expect(messageWarning).toHaveBeenCalledWith(expect.stringContaining('未决'))
    expect(messageSuccess).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  /** V2-07：存量 EXPLAINED（REJECTED 假设）重新确认 → 结论由当前假设推导。 */
  it('EXPLAINED 存量覆盖重新确认时提交合理解释结论并绑定当前版本', async () => {
    const wrapper = await mountDetail('HOLD',
      coverage({ conclusion: 'EXPLAINED', analysisSummary: '存量解释分析' }),
      hypothesis(31, 2, 'REJECTED'))
    vi.mocked(updateAlertCoverage).mockResolvedValueOnce({} as never)
    promptMock.mockResolvedValueOnce({ value: '存量解释覆盖的重新确认分析' })

    await clickButton(wrapper, '重新确认')

    expect(updateAlertCoverage).toHaveBeenCalledTimes(1)
    expect(updateAlertCoverage).toHaveBeenCalledWith(7, 11, {
      expectedRevision: 1, hypothesisId: 31, expectedHypothesisRevision: 2,
      conclusion: 'EXPLAINED', analysisSummary: '存量解释覆盖的重新确认分析',
    })
    expect(messageSuccess).toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('W1/A3-02 假设判断草稿恢复', () => {
  /** BA-01：冲突后刷新失败 → 草稿保留、重开后恢复；不误提示已保存；最终以刷新后版本提交。 */
  it('假设冲突后刷新失败时保留草稿，重开后恢复并最终以新版本提交', async () => {
    const wrapper = await mountDetail('HOLD', pendingCoverage, hypothesis(31, 1, 'OPEN'))
    vi.mocked(updateInvestigationHypothesis)
      .mockRejectedValueOnce(conflictError('HYPOTHESIS', 2))
      .mockRejectedValueOnce(conflictError('HYPOTHESIS', 2))
      .mockResolvedValueOnce({ revision: 2 } as never)
    // 第一次刷新失败（网络错误），第二次刷新成功（乙已改判/确认至 rev 2）
    vi.mocked(getCaseInvestigation)
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(
        investigationView(coverage({}), hypothesis(31, 2, 'CONFIRMED')))
    promptMock.mockResolvedValue({ value: '甲输入的新判断依据内容' })

    await clickButton(wrapper, '确认假设')

    // 首次提交冲突 + 刷新失败：错误提示，未误报成功
    expect(updateInvestigationHypothesis).toHaveBeenCalledTimes(1)
    expect(messageError).toHaveBeenCalled()
    expect(messageSuccess).not.toHaveBeenCalled()

    // 重开：草稿恢复（不是数据库旧依据“当前判断依据”）
    await clickButton(wrapper, '确认假设')
    expect(promptMock.mock.calls[1][2].inputValue).toBe('甲输入的新判断依据内容')

    // 第二轮：仍冲突 → 刷新成功 → 用户确认后以刷新后版本（rev 2）提交成功
    await settle()
    expect(updateInvestigationHypothesis).toHaveBeenCalledTimes(3)
    expect(updateInvestigationHypothesis).toHaveBeenLastCalledWith(7, 31, {
      expectedRevision: 2, status: 'CONFIRMED', rationale: '甲输入的新判断依据内容',
    })
    expect(messageSuccess).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  /** A4-01：写入成功 + 刷新失败 → 提示“已保存”并限制编辑；不重复提交；重载后解除。 */
  it('假设保存成功但刷新失败时如实提示已保存，并阻断基于旧事实的重复提交', async () => {
    const wrapper = await mountDetail('HOLD',
      coverage({ conclusion: 'SUSPICIOUS', hypothesisRevision: 2, revision: 1, analysisSummary: '已确认分析' }),
      hypothesis(31, 2, 'CONFIRMED'))
    // 写入成功（重申后版本 2→3），随后刷新网络失败
    vi.mocked(updateInvestigationHypothesis).mockResolvedValueOnce({ revision: 3 } as never)
    vi.mocked(getCaseInvestigation).mockRejectedValueOnce(new Error('network down'))
    promptMock.mockResolvedValue({ value: '甲重申后输入的新依据内容' })

    await clickButton(wrapper, '重申判断')

    // 写入只发生一次；提示为“已保存”，没有“更新失败”类错误提示
    expect(updateInvestigationHypothesis).toHaveBeenCalledTimes(1)
    expect(updateInvestigationHypothesis).toHaveBeenCalledWith(7, 31, {
      expectedRevision: 2, status: 'CONFIRMED', rationale: '甲重申后输入的新依据内容',
    })
    expect(messageSuccess).toHaveBeenCalled()
    expect(messageWarning).toHaveBeenCalledWith(expect.stringContaining('已保存'))
    expect(messageError).not.toHaveBeenCalled()
    expect(wrapper.html()).toContain('重新加载调查事实')

    // 旧事实不能继续当作当前事实：再次点击被阻断，写入次数不变
    await clickButton(wrapper, '重申判断')
    expect(updateInvestigationHypothesis).toHaveBeenCalledTimes(1)

    // 显式重新加载成功后解除限制（横幅消失）
    vi.mocked(getCaseInvestigation).mockResolvedValue(
      investigationView(coverage({ conclusion: 'SUSPICIOUS', hypothesisRevision: 3, revision: 1 }),
        hypothesis(31, 3, 'CONFIRMED')))
    await clickButton(wrapper, '重新加载调查事实')
    await settle()
    expect(wrapper.html()).not.toContain('重新加载调查事实')
    wrapper.unmount()
  })

  /** A4-01（覆盖路径）：写入成功 + 刷新失败 → 同样如实提示并阻断。 */
  it('覆盖保存成功但刷新失败时如实提示已保存，并阻断基于旧事实的重复提交', async () => {
    const wrapper = await mountDetail('HOLD', legacyDecidedCoverage)
    vi.mocked(updateAlertCoverage).mockResolvedValueOnce({} as never)
    vi.mocked(getCaseInvestigation).mockRejectedValueOnce(new Error('network down'))
    promptMock.mockResolvedValue({ value: '甲重新确认的覆盖分析内容' })

    await clickButton(wrapper, '重新确认')

    expect(updateAlertCoverage).toHaveBeenCalledTimes(1)
    expect(messageSuccess).toHaveBeenCalled()
    expect(messageWarning).toHaveBeenCalledWith(expect.stringContaining('已保存'))
    expect(messageError).not.toHaveBeenCalled()
    expect(wrapper.html()).toContain('重新加载调查事实')

    await clickButton(wrapper, '重新确认')
    expect(updateAlertCoverage).toHaveBeenCalledTimes(1)
    expect(promptMock).toHaveBeenCalledTimes(1)

    vi.mocked(getCaseInvestigation).mockResolvedValue(
      investigationView(coverage({ conclusion: 'SUSPICIOUS', hypothesisRevision: 2, revision: 2 }),
        hypothesis(31, 2, 'CONFIRMED')))
    await clickButton(wrapper, '重新加载调查事实')
    await settle()
    expect(wrapper.html()).not.toContain('重新加载调查事实')
    wrapper.unmount()
  })

  /** 成功后草稿清理：重开时预填数据库最新依据，而不是残留草稿。 */
  it('保存成功后草稿被清理，重开预填数据库当前依据', async () => {
    const wrapper = await mountDetail('HOLD', pendingCoverage, hypothesis(31, 1, 'OPEN'))
    vi.mocked(updateInvestigationHypothesis).mockResolvedValueOnce({ revision: 2 } as never)
    // 保存成功后假设又被他人更新（刷新视图 rationale 为乙的最新依据）
    vi.mocked(getCaseInvestigation).mockResolvedValueOnce(
      investigationView(coverage({ conclusion: 'SUSPICIOUS', hypothesisRevision: 2, revision: 1 }),
        hypothesis(31, 2, 'CONFIRMED', '乙更新后的最新判断依据')))
    promptMock.mockResolvedValueOnce({ value: '本次已成功保存的判断依据内容' })

    await clickButton(wrapper, '确认假设')
    expect(messageSuccess).toHaveBeenCalled()

    // 重开“修改判断”：预填数据库当前依据，而非残留草稿（草稿已被成功保存清理）
    await clickButton(wrapper, '修改判断')
    expect(promptMock).toHaveBeenCalledTimes(2)
    expect(promptMock.mock.calls[1][2].inputValue).toBe('乙更新后的最新判断依据')
    // 用户取消后草稿清理：再次重开仍预填数据库依据
    promptMock.mockRejectedValueOnce('cancel')
    await clickButton(wrapper, '修改判断')
    expect(promptMock).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })
})
