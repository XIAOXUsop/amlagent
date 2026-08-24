import { beforeEach, describe, expect, it, vi } from 'vitest'
import { parseReport, subscribeAssistantRun, subscribeCase, type CaseItem } from './client'

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
    subscribeCase(7, (event) => events.push(event.stage), undefined, (state) => states.push(state))

    FakeEventSource.last.emit('stage', { stage: 'DONE', content: 'complete' })

    expect(events).toEqual(['DONE'])
    expect(FakeEventSource.last.closed).toBe(true)
    expect(states.at(-1)).toBe('closed')
  })

  it('keeps the stream open for a non-terminal stage', () => {
    subscribeCase(8, () => undefined)
    FakeEventSource.last.emit('stage', { stage: 'REASONING', content: 'working' })
    expect(FakeEventSource.last.closed).toBe(false)
  })

  it('ignores malformed SSE payloads without closing an active stream', () => {
    const events: string[] = []
    subscribeCase(9, (event) => events.push(event.stage))
    FakeEventSource.last.listeners.get('stage')?.({ data: '{bad-json' } as MessageEvent<string>)
    expect(events).toEqual([])
    expect(FakeEventSource.last.closed).toBe(false)
  })

  it('explicit unsubscribe closes the stream and exposes closed state', () => {
    const states: string[] = []
    const unsubscribe = subscribeCase(10, () => undefined, undefined, (state) => states.push(state))
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
    subscribeAssistantRun('run/a', text => deltas.push(text), event => terminals.push(event.type))

    expect(FakeEventSource.last.url).toBe('/api/assistant/runs/run%2Fa/events')
    FakeEventSource.last.emit('delta', { text: '风险' })
    FakeEventSource.last.emit('delta', { text: '分析' })
    FakeEventSource.last.emit('completed', { messageId: 'm-1' })

    expect(deltas).toEqual(['风险', '分析'])
    expect(terminals).toEqual(['completed'])
    expect(FakeEventSource.last.closed).toBe(true)
  })

  it('ignores malformed deltas and relies on terminal reconciliation', () => {
    const deltas: string[] = []
    const terminals: string[] = []
    subscribeAssistantRun('run-2', text => deltas.push(text), event => terminals.push(event.type))
    FakeEventSource.last.listeners.get('delta')?.({ data: '{bad' } as MessageEvent<string>)
    FakeEventSource.last.emit('failed', { code: 'MODEL_ERROR' })
    expect(deltas).toEqual([])
    expect(terminals).toEqual(['failed'])
  })
})

describe('parseReport', () => {
  it('returns null for empty or malformed persisted JSON', () => {
    const base = { reportJson: null } as CaseItem
    expect(parseReport(base)).toBeNull()
    expect(parseReport({ ...base, reportJson: '{bad' })).toBeNull()
  })

  it('parses a valid final report', () => {
    const item = { reportJson: JSON.stringify({ riskLevel: '高风险', actionCodes: ['MANUAL_REVIEW'] }) } as CaseItem
    expect(parseReport(item)).toMatchObject({ riskLevel: '高风险', actionCodes: ['MANUAL_REVIEW'] })
  })
})
