import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 后端 REST API 与 SSE 实时推送
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 大依赖拆独立 chunk，降低首屏主包并利用浏览器长期缓存
        manualChunks(id: string) {
          if (id.includes('node_modules/element-plus')) return 'element-plus'
          if (id.includes('node_modules/vue') || id.includes('node_modules/axios')) return 'vue-vendor'
        },
      },
    },
  },
})
