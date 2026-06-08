<template>
  <div class="app-shell">
    <!-- Sidebar -->
    <aside class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-mark">
          <svg viewBox="0 0 32 32" fill="none" class="brand-icon">
            <circle cx="16" cy="16" r="14" stroke="currentColor" stroke-width="1.5" opacity="0.3"/>
            <path d="M8 16h16M16 8v16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
            <circle cx="8" cy="16" r="3" fill="currentColor"/>
            <circle cx="16" cy="8" r="3" fill="currentColor"/>
            <circle cx="24" cy="16" r="3" fill="currentColor"/>
            <circle cx="16" cy="24" r="3" fill="currentColor"/>
            <path d="M16 16l-4 4m4-4l4 4m-4-4l4-4m-4 4l-4-4" stroke="currentColor" stroke-width="0.8" opacity="0.4"/>
          </svg>
        </div>
        <div class="brand-text">
          <span class="brand-name">DataSync</span>
          <span class="brand-desc">同步平台</span>
        </div>
      </div>

      <el-menu :default-active="route.path" router class="sidebar-menu">
        <el-menu-item index="/">
          <el-icon><Monitor /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/datasources">
          <el-icon><Connection /></el-icon>
          <span>数据源管理</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>同步任务</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer">
        <div class="sidebar-status">
          <span class="status-indicator"></span>
          <span class="status-label">系统运行中</span>
        </div>
      </div>

      <!-- Decorative data-flow lines -->
      <div class="sidebar-decoration" aria-hidden="true">
        <div class="flow-line line-1"></div>
        <div class="flow-line line-2"></div>
      </div>
    </aside>

    <!-- Main Content -->
    <div class="main-area">
      <header class="topbar">
        <div class="topbar-left">
          <h1 class="topbar-title">{{ pageTitle }}</h1>
        </div>
        <div class="topbar-right">
          <div class="topbar-time">{{ currentTime }}</div>
        </div>
      </header>
      <main class="content-area">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from "vue"
import { useRoute } from "vue-router"
import { Monitor, Connection, List } from "@element-plus/icons-vue"

const route = useRoute()

const pageTitle = computed(() => {
  const map: Record<string, string> = {
    "/": "仪表盘",
    "/datasources": "数据源管理",
    "/datasources/new": "新增数据源",
    "/tasks": "同步任务",
    "/tasks/new": "新增同步任务",
  }
  // Check dynamic routes
  const path = route.path
  if (path.startsWith("/datasources/") && path.endsWith("/edit")) return "编辑数据源"
  if (path.startsWith("/tasks/") && path.endsWith("/edit")) return "编辑同步任务"
  if (path.startsWith("/tasks/") && path.endsWith("/records")) return "执行历史"
  return map[path] || "数据同步管理平台"
})

const currentTime = ref("")
let timer: ReturnType<typeof setInterval> | null = null

function updateTime() {
  const now = new Date()
  currentTime.value = now.toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  })
}

onMounted(() => {
  updateTime()
  timer = setInterval(updateTime, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  background: var(--bg-deepest);
}

/* ===== Sidebar ===== */
.sidebar {
  width: 240px;
  min-width: 240px;
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}

/* Decorative data-flow animation on sidebar edge */
.sidebar-decoration {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 1px;
  pointer-events: none;
}
.flow-line {
  position: absolute;
  left: 0;
  width: 1px;
  height: 40px;
  background: linear-gradient(to bottom, transparent, var(--accent-teal), transparent);
  opacity: 0.4;
  animation: flowDown 4s ease-in-out infinite;
}
.flow-line.line-2 {
  animation-delay: 2s;
  height: 60px;
  opacity: 0.2;
}
@keyframes flowDown {
  0% { transform: translateY(-100px); opacity: 0; }
  50% { opacity: 0.5; }
  100% { transform: translateY(100vh); opacity: 0; }
}

/* Brand */
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 20px 20px;
  border-bottom: 1px solid var(--border-subtle);
}
.brand-mark {
  width: 36px;
  height: 36px;
  color: var(--accent-teal);
  flex-shrink: 0;
}
.brand-icon {
  width: 100%;
  height: 100%;
}
.brand-text {
  display: flex;
  flex-direction: column;
}
.brand-name {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 18px;
  color: var(--text-primary);
  letter-spacing: -0.02em;
  line-height: 1.2;
}
.brand-desc {
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

/* Menu */
.sidebar-menu {
  flex: 1;
  padding: 12px 0;
  background: transparent !important;
  border: none !important;
}
.sidebar-menu .el-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 42px;
  line-height: 42px;
  padding: 0 16px;
  margin: 2px 10px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  position: relative;
}
.sidebar-menu .el-menu-item.is-active {
  background: var(--accent-teal-dim) !important;
}
.sidebar-menu .el-menu-item.is-active::before {
  content: "";
  position: absolute;
  left: -10px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background: var(--accent-teal);
  border-radius: 0 3px 3px 0;
  box-shadow: 0 0 8px var(--accent-teal);
}

/* Footer */
.sidebar-footer {
  padding: 16px 20px;
  border-top: 1px solid var(--border-subtle);
}
.sidebar-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.status-indicator {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent-emerald);
  box-shadow: 0 0 8px var(--accent-emerald-dim);
  animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

/* ===== Main Area ===== */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* Topbar */
.topbar {
  height: 60px;
  min-height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-subtle);
}
.topbar-title {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.topbar-time {
  font-family: var(--font-code);
  font-size: 14px;
  color: var(--text-muted);
  letter-spacing: 0.05em;
}

/* Content */
.content-area {
  flex: 1;
  padding: 24px 32px;
  overflow-y: auto;
  overflow-x: hidden;
}
</style>
