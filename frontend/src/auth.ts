import { ref } from 'vue'
import type { AuthenticatedUser } from './api/client'

/** 模块级认证状态：App 视图与路由守卫共享当前登录用户 */
export const currentUser = ref<AuthenticatedUser | null>(null)
