import { describe, it, expect } from 'vitest'
import router from './index'

describe('router', () => {
  it('注册四个核心路由', () => {
    const paths = router.getRoutes().map((r) => r.path)
    expect(paths).toContain('/cases')
    expect(paths).toContain('/cases/:id')
    expect(paths).toContain('/reviews')
    expect(paths).toContain('/eval')
  })

  it('根路径重定向到工单中心', () => {
    const root = router.getRoutes().find((r) => r.path === '/')
    expect(root?.redirect).toBe('/cases')
  })
})
