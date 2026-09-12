import { describe, expect, it } from 'vitest'
import { ApiContractError } from './contracts'
import {
  parseAssistantDelta,
  parseAssistantTerminalPayload,
  parseDueDiligenceReport,
  parseSseToken,
  parseWorkflowEvent,
  validateApiErrorResponse,
  validateApiResponse,
} from './response-contracts'

describe('route-level API response contracts', () => {
  it('rejects a missing required field', () => {
    expect(() => validateApiResponse({ status: 'DONE' }, '/cases/7', 'get')).toThrow(ApiContractError)
  })

  it('rejects an unknown enum value', () => {
    const alert = {
      id: 1,
      externalAlertId: 'A-1',
      customerId: 'C-1',
      ruleCode: 'R-1',
      scenarioCode: 'STRUCTURING',
      hitReason: 'test',
      occurredAt: '2026-09-10T01:00:00Z',
      status: 'BROKEN',
      caseId: null,
      revision: 0,
      resolutionReason: null,
      createdBy: 'system',
      createdAt: '2026-09-10T01:00:00Z',
      updatedAt: '2026-09-10T01:00:00Z',
    }

    expect(() => validateApiResponse([alert], '/alerts', 'get')).toThrow(/枚举值无效/)
  })

  it('rejects timestamps without an RFC 3339 offset', () => {
    const operation = {
      caseId: 7,
      customerId: 'C-1',
      customerName: '客户',
      caseStatus: 'HOLD',
      priority: 'HIGH',
      priorityScore: 80,
      priorityReasons: [],
      priorityPolicy: 'v1',
      phase: 'REVIEW',
      responsibleRole: 'REVIEWER',
      assignedTo: null,
      assignedUnit: null,
      clockStartedAt: '2026-09-10T01:00:00',
      dueAt: null,
      overdue: false,
      minutesRemaining: 60,
      slaPolicy: 'v1',
      calculatedAt: '2026-09-10T01:00:00Z',
    }

    expect(() => validateApiResponse(operation, '/case-operations/7', 'get')).toThrow(/RFC 3339/)
  })

  it('rejects invalid nested monetary values', () => {
    const windows = {
      asOfTime: '2026-09-10T01:00:00Z',
      sourceSystem: 'CORE',
      sourceVersion: '1',
      windows: [
        {
          days: 30,
          transactionCount: 1,
          currencyBreakdown: [
            {
              currency: 'CNY',
              totalAmount: 10,
              incomingAmount: '10.00',
              outgoingAmount: '0.00',
              crossBorderAmount: '0.00',
            },
          ],
          crossBorderCount: 0,
          nightCount: 0,
          topCounterparties: [],
        },
      ],
    }

    expect(() => validateApiResponse(windows, '/cases/7/investigation/transaction-windows', 'get')).toThrow(
      /应为string/,
    )
  })

  it('fails closed when an endpoint has no registered contract', () => {
    expect(() => validateApiResponse({}, '/unknown-endpoint', 'get')).toThrow(/未注册/)
  })

  it('validates error payloads before UI helpers consume them', () => {
    expect(() =>
      validateApiErrorResponse({
        code: 'VALIDATION_ERROR',
        message: '参数校验失败',
        traceId: 'trace-1',
        timestamp: '2026-09-10T01:00:00Z',
        fieldErrors: { amount: '必须大于 0' },
      }),
    ).not.toThrow()
    expect(() =>
      validateApiErrorResponse({
        code: 'INTERNAL_ERROR',
        message: '服务器内部错误',
        traceId: 'trace-2',
        timestamp: '2026-09-10T01:00:00',
      }),
    ).toThrow(/RFC 3339/)
  })
})

describe('stream and embedded JSON contracts', () => {
  it('validates workflow and token events exactly', () => {
    expect(parseWorkflowEvent({ caseId: 7, stage: 'DONE', content: 'ok' })).toEqual({
      caseId: 7,
      stage: 'DONE',
      content: 'ok',
    })
    expect(parseSseToken({ token: '片段' })).toBe('片段')
    expect(() => parseAssistantDelta({ text: 1 })).toThrow(ApiContractError)
    expect(parseAssistantTerminalPayload('failed', { runId: 'run-1', errorCode: 'MODEL_ERROR' })).toEqual({
      runId: 'run-1',
      errorCode: 'MODEL_ERROR',
    })
    expect(() => parseAssistantTerminalPayload('completed', { runId: 'run-1' })).toThrow(ApiContractError)
  })

  it('rejects malformed embedded due-diligence reports', () => {
    expect(() =>
      parseDueDiligenceReport({
        customerId: 'C-1',
        customerName: '客户',
        riskLevel: 'HIGH',
        transactionProfile: 'profile',
        corporateProfile: 'corp',
        sanctions: [],
        legalBasis: [],
        riskPoints: [],
        conclusion: 'review',
        evidenceChain: [],
        manualReviewRequired: 'yes',
        findingCodes: [],
        actionCodes: [],
      }),
    ).toThrow(ApiContractError)
  })
})
