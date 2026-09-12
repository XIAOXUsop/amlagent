import axios from 'axios'
import { validateApiErrorResponse, validateApiResponse } from './response-contracts'

function requestTimeoutMs(): number {
  const timeout = Number(import.meta.env.VITE_API_TIMEOUT_MS)
  if (!Number.isInteger(timeout) || timeout <= 0) {
    throw new Error('VITE_API_TIMEOUT_MS must be a positive integer')
  }
  return timeout
}

export const api = axios.create({ baseURL: '/api', timeout: requestTimeoutMs() })

// CSRF 防护：从可读的 XSRF-TOKEN Cookie 取值，写入 X-XSRF-TOKEN header（写请求）
function readCsrfToken(): string {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]+)/.exec(document.cookie)
  return match ? match[1] : ''
}

// 认证走 HttpOnly Cookie（浏览器自动携带），不再手动附加 Authorization 头
api.interceptors.request.use((config) => {
  const method = (config.method ?? 'GET').toUpperCase()
  if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
    config.headers['X-XSRF-TOKEN'] = readCsrfToken()
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    if (response.data !== '' && response.data !== undefined) {
      validateApiResponse(response.data, response.config.url ?? '', response.config.method)
    }
    return response
  },
  (err) => {
    if (err.response?.data !== '' && err.response?.data !== undefined) {
      try {
        validateApiErrorResponse(err.response.data)
      } catch (contractError) {
        return Promise.reject(
          contractError instanceof Error
            ? contractError
            : new Error('API error response contract validation failed', { cause: contractError }),
        )
      }
    }
    // /auth/me 返回 401 表示"未登录"这一正常状态（初始加载校验），不应触发整页跳转，
    // 否则会与 App 挂载时的 checkAuth 形成 401 → 跳转 → 再校验 的无限循环。
    if (err.response?.status === 401 && !err.config?.url?.includes('/auth/me')) {
      // 通过自定义事件通知 App 清理登录态并回到登录界面（避免整页跳转丢失当前工作上下文）
      window.dispatchEvent(new CustomEvent('auth:expired'))
    }
    return Promise.reject(err instanceof Error ? err : new Error('API request failed', { cause: err }))
  },
)
