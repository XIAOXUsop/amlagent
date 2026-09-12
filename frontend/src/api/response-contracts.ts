import { ApiContractError } from './contracts'
import { object, string, validate } from './contract-runtime'
import type { DueDiligenceReport, WorkflowEvent } from './models'
import { routes } from './response-route-catalog'
import { responseSchemas } from './response-schema-catalog'
import { assistantTerminalSchema, type AssistantTerminalType } from './specialized-response-schemas'

const { apiError, dueDiligenceReport, workflowEvent } = responseSchemas

export function validateApiResponse(value: unknown, url: string, method = 'get'): void {
  const path = url.split('?')[0]
  const normalizedMethod = method.toLowerCase()
  const route = routes.find((candidate) => candidate.method === normalizedMethod && candidate.path.test(path))
  if (route) {
    validate(value, route.schema, route.name, '$')
    return
  }
  throw new ApiContractError(`${normalizedMethod.toUpperCase()} ${path}`, '未注册运行时响应契约')
}

export function validateApiErrorResponse(value: unknown): void {
  validate(value, apiError, 'ApiError', '$')
}

export function parseWorkflowEvent(value: unknown): WorkflowEvent {
  validate(value, workflowEvent, 'WorkflowEvent', '$')
  return value as WorkflowEvent
}

export function parseSseToken(value: unknown): string {
  const schema = object({ token: string() })
  validate(value, schema, 'WorkflowTokenEvent', '$')
  return (value as { token: string }).token
}

export function parseAssistantDelta(value: unknown): string {
  const schema = object({ text: string() })
  validate(value, schema, 'AssistantDeltaEvent', '$')
  return (value as { text: string }).text
}

export interface AssistantTerminalPayloads {
  completed: { runId: string; resultType: string }
  refused: { runId: string; resultType: string; message: string }
  failed: { runId: string; errorCode: string }
}

export function parseAssistantTerminalPayload<T extends AssistantTerminalType>(
  type: T,
  value: unknown,
): AssistantTerminalPayloads[T] {
  validate(value, assistantTerminalSchema(type), `AssistantTerminalEvent:${type}`, '$')
  return value as AssistantTerminalPayloads[T]
}

export function parseDueDiligenceReport(value: unknown): DueDiligenceReport {
  validate(value, dueDiligenceReport, 'DueDiligenceReport', '$')
  return value as DueDiligenceReport
}
