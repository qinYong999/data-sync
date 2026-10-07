const { defineConfig } = require("vite");
const vue = require("@vitejs/plugin-vue");
const path = require("path");

/**
 * 手动分包：把体积大、更新频率低的第三方依赖拆成独立 chunk，
 * 让浏览器缓存更有效、首屏并行下载更充分。
 * 只按「依赖来源」切分，不碰业务代码，避免引入循环 chunk。
 */
function manualChunks(id) {
  if (!id.includes("node_modules")) return undefined;
  const p = id.replace(/\\/g, "/");
  if (p.includes("/element-plus/") || p.includes("/@element-plus/")) return "element-plus";
  if (p.includes("/vue/") || p.includes("/@vue/") || p.includes("/vue-router/") || p.includes("/@vueuse/")) {
    return "vue";
  }
  return "vendor";
}

module.exports = defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  build: {
    // 拆包后 element-plus 单 chunk 仍约 800KB（整库引入的固有体积），提高阈值避免噪音告警
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: { manualChunks },
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
  // 预览构建产物时同样代理后端，便于对 dist/ 做端到端验收
  preview: {
    port: 4173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
