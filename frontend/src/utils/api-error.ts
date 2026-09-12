import axios from 'axios'

interface ApiErrorPayload {
  code?: unknown
  message?: unknown
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError<ApiErrorPayload>(error)) return fallback
  const message = error.response?.data.message
  return typeof message === 'string' && message.length > 0 ? message : fallback
}

export function apiErrorCode(error: unknown): string | undefined {
  if (!axios.isAxiosError<ApiErrorPayload>(error)) return undefined
  const code = error.response?.data.code
  return typeof code === 'string' ? code : undefined
}

export function isApiErrorStatus(error: unknown, status: number): boolean {
  return axios.isAxiosError(error) && error.response?.status === status
}

export function isDialogCancellation(error: unknown): boolean {
  return error === 'cancel' || error === 'close'
}
