import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  api,
  createCase,
  createCaseFromAlert,
  parseReport,
  splitAlertToNewCase,
  subscribeAssistantRun,
  subscribeCase,
  updateAlertCoverage,
  type AssistantRunTerminalEvent,
  type CaseItem,
} from './client'

class FakeEventSource {
  static last: FakeEventSource
  static readonly CLOSED = 2
  readyState = 1
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  listeners = new Map<string, (event: MessageEvent<string>) => void>()
  readonly url: string

  constructor(url: string) {
    this.url = url
    FakeEventSource.last = this
  }

  addEventListener(name: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.set(name, listener)
  }

  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }

  emit(name: string, payload: unknown) {
    this.listeners.get(name)?.({ data: JSON.stringify(payload) } as MessageEvent<string>)
  }
}

describe('subscribeCase', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  it('closes the browser stream after a terminal workflow event', () => {
    const states: string[] = []
    const events: string[] = []
    subscribeCase(
      7,
      (event) => events.push(event.stage),
      undefined,
      (state) => states.push(state),
    )

    FakeEventSource.last.emit('stage', { caseId: 7, stage: 'DONE', content: 'complete' })

    expect(events).toEqual(['DONE'])
    expect(FakeEventSource.last.closed).toBe(true)
    expect(states.at(-1)).toBe('closed')
  })

  it('keeps the stream open for a non-terminal stage', () => {
    subscribeCase(8, () => undefined)
    FakeEventSource.last.emit('stage', { caseId: 8, stage: 'REASONING', content: 'working' })
    expect(FakeEventSource.last.closed).toBe(false)
  })

  it('fails closed and reports malformed SSE payloads', () => {
    const events: string[] = []
    const states: string[] = []
    const protocolErrors: string[] = []
    subscribeCase(
      9,
      (event) => events.push(event.stage),
      undefined,
      (state) => states.push(state),
      (eventType) => protocolErrors.push(eventType),
    )
    FakeEventSource.last.listeners.get('stage')?.({ data: '{bad-json' } as MessageEvent<string>)
    expect(events).toEqual([])
    expect(FakeEventSource.last.closed).toBe(true)
    expect(states.at(-1)).toBe('closed')
    expect(protocolErrors).toEqual(['stage'])
  })

  it('explicit unsubscribe closes the stream and exposes closed state', () => {
    const states: string[] = []
    const unsubscribe = subscribeCase(
      10,
      () => undefined,
      undefined,
      (state) => states.push(state),
    )
    unsubscribe()
    expect(FakeEventSource.last.closed).toBe(true)
    expect(states.at(-1)).toBe('closed')
  })
})

describe('subscribeAssistantRun', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  it('appends delta text and closes exactly at a terminal event', () => {
    const deltas: string[] = []
    const terminals: string[] = []
    subscribeAssistantRun(
      'run/a',
      (text) => deltas.push(text),
      (event) => terminals.push(event.type),
    )

    expect(FakeEventSource.last.url).toBe('/api/assistant/runs/run%2Fa/events')
    FakeEventSource.last.emit('delta', { text: '风险' })
    FakeEventSource.last.emit('delta', { text: '分析' })
    FakeEventSource.last.emit('completed', { runId: 'run/a', resultType: 'ANSWERED' })

    expect(deltas).toEqual(['风险', '分析'])
    expect(terminals).toEqual(['completed'])
    expect(FakeEventSource.last.closed).toBe(true)
  })

  it('fails closed on malformed deltas and requests persistent-message reconciliation', () => {
    const deltas: string[] = []
    const terminals: AssistantRunTerminalEvent[] = []
    subscribeAssistantRun(
      'run-2',
      (text) => deltas.push(text),
      (event) => terminals.push(event),
    )
    FakeEventSource.last.listeners.get('delta')?.({ data: '{bad' } as MessageEvent<string>)
    FakeEventSource.last.emit('failed', { code: 'MODEL_ERROR' })
    expect(deltas).toEqual([])
    expect(terminals).toEqual([{ type: 'contract-error', sourceType: 'delta' }])
    expect(FakeEventSource.last.closed).toBe(true)
  })
})

describe('parseReport', () => {
  it('returns null for empty or malformed persisted JSON', () => {
    const base = { reportJson: null } as CaseItem
    expect(parseReport(base)).toBeNull()
    expect(parseReport({ ...base, reportJson: '{bad' })).toBeNull()
  })

  it('parses a valid final report', () => {
    const item = {
      reportJson: JSON.stringify({
        customerId: 'C001',
        customerName: '测试客户',
        riskLevel: '高风险',
        transactionProfile: '跨境交易活跃',
        corporateProfile: '贸易企业',
        sanctions: [],
        legalBasis: [],
        riskPoints: ['交易行为异常'],
        conclusion: '需要人工复核',
        evidenceChain: ['TX-1'],
        manualReviewRequired: true,
        findingCodes: ['PROFILE_MISMATCH'],
        actionCodes: ['MANUAL_REVIEW'],
      }),
    } as CaseItem
    expect(parseReport(item)).toMatchObject({ riskLevel: '高风险', actionCodes: ['MANUAL_REVIEW'] })
  })
})

describe('case start options', () => {
  /** T14：建案/拆分的两种模式必须显式传 autoProcess，防止调用点遗漏后悄悄恢复立即入队。 */
  it('passes autoProcess explicitly for create / create-from-alert / split', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { id: 1 } })
    try {
      await createCase('C001', '常规监测', { autoProcess: false })
      expect(post).toHaveBeenLastCalledWith('/cases', { customerId: 'C001', alertRule: '常规监测', autoProcess: false })

      await createCaseFromAlert(3, 2, { autoProcess: false })
      expect(post).toHaveBeenLastCalledWith('/alerts/3/create-case', { expectedRevision: 2, autoProcess: false })

      await createCaseFromAlert(3, 2, { autoProcess: true })
      expect(post).toHaveBeenLastCalledWith('/alerts/3/create-case', { expectedRevision: 2, autoProcess: true })

      await splitAlertToNewCase(3, 2, '该预警的交易主体应独立处理', { autoProcess: false })
      expect(post).toHaveBeenLastCalledWith('/alerts/3/split', {
        expectedRevision: 2,
        reason: '该预警的交易主体应独立处理',
        autoProcess: false,
      })
    } finally {
      post.mockRestore()
    }
  })

  /** T14：覆盖更新必须携带假设版本，旧客户端缺字段会被后端明确拒绝。 */
  it('sends expectedHypothesisRevision with coverage updates', async () => {
    const put = vi.spyOn(api, 'put').mockResolvedValue({ data: {} })
    try {
      await updateAlertCoverage(7, 11, {
        expectedRevision: 1,
        hypothesisId: 21,
        expectedHypothesisRevision: 3,
        conclusion: 'SUSPICIOUS',
        analysisSummary: '与已确认假设一致的覆盖结论说明',
      })
      expect(put).toHaveBeenCalledWith(
        '/cases/7/investigation/alerts/11/coverage',
        expect.objectContaining({ expectedHypothesisRevision: 3 }),
      )
    } finally {
      put.mockRestore()
    }
  })
})
