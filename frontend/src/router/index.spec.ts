import { describe, it, expect } from 'vitest'
import router from './index'

describe('router', () => {
  it('注册核心业务与管理路由', () => {
    const paths = router.getRoutes().map((r) => r.path)
    expect(paths).toContain('/cases')
    expect(paths).toContain('/cases/:id')
    expect(paths).toContain('/reviews')
    expect(paths).toContain('/eval')
    expect(paths).toContain('/customers')
    expect(paths).toContain('/customers/:id')
  })

  it('根路径重定向到工单中心', () => {
    const root = router.getRoutes().find((r) => r.path === '/')
    expect(root?.redirect).toBe('/cases')
  })
})
