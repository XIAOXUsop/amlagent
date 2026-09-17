import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需自动导入：组件与 API（含样式）按使用引入，降低主包体积。
    //
    // 注意：这两个插件只扫描 .vue 文件里**内联**的 <template>。
    // 曾经把视图模板抽成 <template src="./templates/xxx.template.html">，
    // 结果外置模板用到的 el-* 全部解析失败、页面渲染成空白——
    // 而构建、类型检查、单元测试全部照常通过，只有真打开页面才看得到。
    // 所以视图模板保持内联；要抽出去的话，必须同时解决自动导入的可见性。
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
    }),
  ],
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
    // Element Plus 已单独拆为长期缓存 vendor chunk；558 kB 原始体积约 182 kB gzip。
    chunkSizeWarningLimit: 600,
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
