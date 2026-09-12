import { describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { apiErrorCode, apiErrorMessage, isApiErrorStatus, isDialogCancellation } from './api-error'

function apiError(status: number, data: unknown): AxiosError {
  return new AxiosError('request failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    data,
    status,
    statusText: 'Error',
    headers: {},
    config: { headers: new AxiosHeaders() },
  })
}

describe('API error helpers', () => {
  it('extracts safe fields from Axios errors', () => {
    const error = apiError(409, { code: 'REVISION_CONFLICT', message: '版本冲突' })

    expect(apiErrorMessage(error, 'fallback')).toBe('版本冲突')
    expect(apiErrorCode(error)).toBe('REVISION_CONFLICT')
    expect(isApiErrorStatus(error, 409)).toBe(true)
  })

  it('uses fallbacks for untrusted error payloads', () => {
    expect(apiErrorMessage(new Error('internal details'), '请求失败')).toBe('请求失败')
    expect(apiErrorCode(apiError(400, { code: 123 }))).toBeUndefined()
  })

  it('recognizes Element Plus dialog cancellation values', () => {
    expect(isDialogCancellation('cancel')).toBe(true)
    expect(isDialogCancellation('close')).toBe(true)
    expect(isDialogCancellation(new Error('cancel'))).toBe(false)
  })
})
