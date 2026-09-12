import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 组件测试环境：只启用 Vue SFC 编译。
// 刻意不引入 Element Plus 按需导入插件（其样式导入在 node ESM 下无法解析）；
// 模板中的 el-* 组件在测试内按原样渲染，文本断言不受影响（见 views/CaseDetailView.spec.ts）。
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.ts'],
    coverage: {
      reporter: ['text', 'html', 'json-summary'],
      thresholds: {
        statements: 45,
        branches: 35,
        functions: 30,
        lines: 45,
      },
    },
    onConsoleLog(log, type) {
      if (type === 'stderr' && log.includes('[Vue warn]: Failed to resolve component')) {
        return false
      }
    },
  },
})
