import { parseAuthenticatedUser } from './contracts'
import { api } from './http'

// ---------- 认证 ----------
export type UserRole = 'ADMIN' | 'REVIEWER' | 'ANALYST'

export interface AuthenticatedUser {
  username: string
  role: UserRole
}

/** 强制生成 CSRF Token Cookie（登录/恢复登录态后调用，供写请求携带 X-XSRF-TOKEN） */
export async function initCsrf(): Promise<void> {
  await api.get('/auth/csrf')
}

export async function login(username: string, password: string): Promise<AuthenticatedUser> {
  const user = parseAuthenticatedUser<AuthenticatedUser>((await api.post('/auth/login', { username, password })).data)
  await initCsrf()
  return user
}

/** 刷新后恢复登录态（未认证由后端返回 401） */
export async function checkAuth(): Promise<AuthenticatedUser> {
  const user = parseAuthenticatedUser<AuthenticatedUser>((await api.get('/auth/me')).data)
  await initCsrf()
  return user
}

export async function logout(): Promise<void> {
  await api.post('/auth/logout')
}
