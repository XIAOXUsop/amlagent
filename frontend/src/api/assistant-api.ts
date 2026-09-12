import { api } from './http'
import type { Page } from './models'
import type { SseState } from './investigation-api'
import {
  parseAssistantDelta,
  parseAssistantTerminalPayload,
  type AssistantTerminalPayloads,
} from './response-contracts'

// ---------- 当前客户 AI 小助（ADMIN，只读） ----------
export interface AssistantStatus {
  enabled: boolean
  maxMessageChars: number
}

export interface AssistantConversation {
  id: string
  customerId: number
  customerNo: string
  status: 'ACTIVE' | 'ARCHIVED' | 'EXPIRED'
  createdAt: string
  updatedAt: string
  expiresAt: string
}

export interface AssistantMessage {
  id: string
  sequenceNo: number
  role: 'USER' | 'ASSISTANT'
  status: 'ACCEPTED' | 'PROCESSING' | 'COMPLETED' | 'REFUSED' | 'FAILED' | 'BLOCKED'
  resultType: string | null
  content: string
  createdAt: string
  completedAt: string | null
}

export interface AssistantAcceptedRun {
  runId: string
  userMessageId: string
  assistantMessageId: string
  status: 'ACCEPTED'
  idempotentReplay: boolean
}

export async function getAssistantStatus(): Promise<AssistantStatus> {
  return (await api.get('/assistant/status')).data
}

export async function createAssistantConversation(customerId: number): Promise<AssistantConversation> {
  return (await api.post(`/admin/customers/${customerId}/assistant/conversations`)).data
}

export async function listAssistantConversations(customerId: number): Promise<Page<AssistantConversation>> {
  return (await api.get(`/admin/customers/${customerId}/assistant/conversations`, { params: { page: 0, size: 20 } }))
    .data
}

export async function listAssistantMessages(conversationId: string): Promise<AssistantMessage[]> {
  return (await api.get(`/assistant/conversations/${conversationId}/messages`)).data
}

export async function submitAssistantMessage(
  conversationId: string,
  clientMessageId: string,
  content: string,
): Promise<AssistantAcceptedRun> {
  return (await api.post(`/assistant/conversations/${conversationId}/messages`, { clientMessageId, content })).data
}

export async function archiveAssistantConversation(conversationId: string): Promise<void> {
  await api.delete(`/assistant/conversations/${conversationId}`)
}

export type AssistantRunTerminalEvent =
  | { type: 'completed'; payload: AssistantTerminalPayloads['completed'] }
  | { type: 'refused'; payload: AssistantTerminalPayloads['refused'] }
  | { type: 'failed'; payload: AssistantTerminalPayloads['failed'] }
  | { type: 'contract-error'; sourceType: 'delta' | 'completed' | 'refused' | 'failed' }

/** run 级 SSE；浏览器会在同一 EventSource 重连时自动携带 Last-Event-ID。 */
export function subscribeAssistantRun(
  runId: string,
  onDelta: (text: string) => void,
  onTerminal: (event: AssistantRunTerminalEvent) => void,
  onState?: (state: SseState) => void,
): () => void {
  const es = new EventSource(`/api/assistant/runs/${encodeURIComponent(runId)}/events`)
  let terminal = false
  onState?.('connecting')
  es.onopen = () => onState?.('open')
  es.onerror = () => {
    if (!terminal) onState?.('reconnecting')
  }
  es.addEventListener('delta', (event: MessageEvent<string>) => {
    try {
      onDelta(parseAssistantDelta(JSON.parse(event.data)))
    } catch {
      // 增量载荷损坏后不能继续拼接不可信流；关闭连接并要求调用方从持久化消息重新对账。
      terminal = true
      es.close()
      onState?.('closed')
      onTerminal({ type: 'contract-error', sourceType: 'delta' })
    }
  })
  for (const type of ['completed', 'refused', 'failed'] as const) {
    es.addEventListener(type, (event: MessageEvent<string>) => {
      if (terminal) return
      let terminalEvent: AssistantRunTerminalEvent
      try {
        const payload = parseAssistantTerminalPayload(type, JSON.parse(event.data))
        terminalEvent = { type, payload } as AssistantRunTerminalEvent
      } catch {
        // 终态载荷不满足契约时不得伪造空对象；显式标记契约错误，再由调用方回查持久化消息。
        terminalEvent = { type: 'contract-error', sourceType: type }
      }
      terminal = true
      es.close()
      onState?.('closed')
      onTerminal(terminalEvent)
    })
  }
  return () => {
    terminal = true
    es.close()
    onState?.('closed')
  }
}
