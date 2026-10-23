import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'

// 开发态：5173，proxy /api → 后端 8080，免 CORS
// 生产态：npm run build 产物拷到 src/main/resources/static 由 Spring 同源托管
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 直接输出到后端 static 目录（替代手工拷贝）：emptyOutDir 每次构建先清空，
    // 根治 static/assets 里积累多个历史版本 hash 产物的问题
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
