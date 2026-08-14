import { ref } from 'vue'
import type { AuthenticatedUser } from './api/client'

/** 模块级认证状态：App 视图与路由守卫共享当前登录用户 */
export const currentUser = ref<AuthenticatedUser | null>(null)

let resolveAuth: () => void = () => {}
/** 认证初始化完成的 Promise：路由守卫 await 它，避免刷新受限路由时 currentUser 尚未恢复 */
export const authReady = new Promise<void>((resolve) => {
  resolveAuth = resolve
})

export function markAuthReady(): void {
  resolveAuth()
}
