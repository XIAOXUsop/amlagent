import { defineConfig } from '@playwright/test'

/**
 * Playwright E2E：登录、工单查看、复核、权限菜单。
 * 需要后端（8080）与前端（5173）运行；用于本地与 CI 集成验证。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
