import { defineConfig } from '@playwright/test'

/**
 * Playwright E2E：登录、工单创建与尽调闭环、人工复核、权限菜单。
 *
 * 需要后端（8080）运行。后端必须使用 **Mock 模型**：
 * `AML_LLM_ACTIVE_PROVIDER=mock`。真实模型既依赖外网，也让终态不可断言
 * （同一份案例可能这次判高风险、下次判中风险，测试会变成随机红）。
 *
 * 失败时保留截图与 trace，浏览器控制台由用例挂进报告——
 * 没有这些，"CI 挂了"只能靠猜。
 */
export default defineConfig({
  testDir: './e2e',
  // 预热：把 Vite 首次依赖预构建触发的整页重载挪到用例之外（详见 e2e/global-setup.ts）
  globalSetup: './e2e/global-setup.ts',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  outputDir: 'test-results',
  // 串行跑。这些用例共享**服务端状态**：同一个账号、同一个待复核队列、同一批工单。
  // 具体踩过的坑：登出接口会吊销该账号已签发的全部 JWT（单会话策略），
  // 于是并行跑的 auth 用例一点"退出"，另一个用例的会话就跟着失效，
  // 表现为"页面停在待处理不动"这种看上去像后端卡死的现象。
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
