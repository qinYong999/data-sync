<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-icon">
          <svg viewBox="0 0 28 28" fill="none">
            <circle cx="14" cy="14" r="12" stroke="currentColor" stroke-width="1.5" opacity="0.25"/>
            <path d="M6 14h16M14 6v16" stroke="currentColor" stroke-width="2" stroke-linecap="round" opacity="0.6"/>
            <circle cx="6" cy="14" r="2.5" fill="currentColor"/>
            <circle cx="14" cy="6" r="2.5" fill="currentColor"/>
            <circle cx="22" cy="14" r="2.5" fill="currentColor"/>
            <circle cx="14" cy="22" r="2.5" fill="currentColor"/>
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-name">DataSync</div>
          <div class="brand-desc">同步平台</div>
        </div>
      </div>

      <el-menu :default-active="route.path" router>
        <el-menu-item index="/">
          <el-icon><Monitor /></el-icon><span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/datasources">
          <el-icon><Connection /></el-icon><span>数据源管理</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon><span>同步任务</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer">
        <div class="sidebar-status">
          <span class="status-indicator"></span>
          <span class="status-label">系统运行中</span>
        </div>
      </div>
    </aside>

    <div class="main-area">
      <header class="topbar">
        <h1 class="topbar-title">{{ pageTitle }}</h1>
        <div class="topbar-time mono">{{ currentTime }}</div>
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
  const path = route.path
  const staticMap: Record<string, string> = {
    "/": "仪表盘",
    "/datasources": "数据源管理",
    "/datasources/new": "新增数据源",
    "/tasks": "同步任务",
    "/tasks/new": "新增同步任务",
  }
  if (path.startsWith("/datasources/") && path.endsWith("/edit")) return "编辑数据源"
  if (path.startsWith("/tasks/") && path.endsWith("/edit")) return "编辑同步任务"
  if (path.startsWith("/tasks/") && path.endsWith("/records")) return "执行历史"
  return staticMap[path] || "数据同步管理平台"
})

const currentTime = ref("")
let timer: ReturnType<typeof setInterval> | null = null

function updateTime() {
  currentTime.value = new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false })
}

onMounted(() => { updateTime(); timer = setInterval(updateTime, 1000) })
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.app-shell { display: flex; height: 100vh; background: var(--bg-deepest); }

/* Sidebar */
.sidebar {
  width: 220px; min-width: 220px;
  background: var(--bg-card);
  border-right: 1px solid var(--border-subtle);
  display: flex; flex-direction: column;
  box-shadow: 1px 0 4px rgba(26, 29, 35, 0.04);
}

.sidebar-brand {
  display: flex; align-items: center; gap: 10px;
  padding: 20px 16px 16px;
  border-bottom: 1px solid var(--border-subtle);
}
.brand-icon { width: 30px; height: 30px; color: var(--accent); flex-shrink: 0; }
.brand-icon svg { width: 100%; height: 100%; }
.brand-text { display: flex; flex-direction: column; }
.brand-name { font-weight: 700; font-size: 16px; color: var(--text-primary); line-height: 1.2; }
.brand-desc { font-size: 11px; color: var(--text-muted); letter-spacing: 0.06em; text-transform: uppercase; }

.sidebar .el-menu { flex: 1; padding: 8px 0; background: transparent !important; border: none !important; }
.sidebar .el-menu-item { gap: 8px; height: 38px; line-height: 38px; padding: 0 12px; margin: 2px 8px; border-radius: var(--radius-sm); font-size: 14px; }
.sidebar .el-menu-item.is-active { background: var(--accent-dim) !important; position: relative; }
.sidebar .el-menu-item.is-active::before {
  content: ""; position: absolute; left: -8px; top: 50%; transform: translateY(-50%);
  width: 3px; height: 16px; background: var(--accent); border-radius: 0 3px 3px 0;
}

.sidebar-footer { padding: 12px 16px; border-top: 1px solid var(--border-subtle); }
.sidebar-status { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-muted); }
.status-indicator { width: 6px; height: 6px; border-radius: 50%; background: var(--accent-emerald); }

/* Main Area */
.main-area { flex: 1; display: flex; flex-direction: column; min-width: 0; }

.topbar {
  height: 52px; min-height: 52px;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 28px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border-subtle);
}
.topbar-title { font-size: 17px; font-weight: 600; letter-spacing: -0.01em; }
.topbar-time { font-size: 13px; color: var(--text-muted); letter-spacing: 0.04em; }

.content-area { flex: 1; padding: 20px 28px; overflow-y: auto; }
</style>
