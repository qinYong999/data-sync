<template>
  <!-- 登录页等"裸页面"不套壳 -->
  <router-view v-if="isBlank" />

  <div v-else class="app-shell">
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
          <span class="status-indicator" :class="{ warn: runningTasks > 0 }"></span>
          <span class="status-label">
            {{ runningTasks > 0 ? runningTasks + " 个任务运行中" : "系统空闲" }}
          </span>
        </div>
        <div v-if="systemInfo" class="sidebar-meta">
          <span class="mono">{{ systemInfo.version || "—" }}</span>
          <span class="meta-sep">·</span>
          <span>{{ systemInfo.dbType || "—" }}</span>
        </div>
        <div v-if="systemInfo && systemInfo.dm8Verified === false" class="sidebar-warn">
          <el-icon><WarningFilled /></el-icon>
          <span>DM8 未在真实实例验证</span>
        </div>
      </div>
    </aside>

    <div class="main-area">
      <header class="topbar">
        <h1 class="topbar-title">{{ pageTitle }}</h1>
        <div class="topbar-right">
          <div class="topbar-time mono">{{ currentTime }}</div>
          <el-divider direction="vertical" />
          <el-dropdown trigger="click" @command="onUserCommand">
            <span class="user-chip">
              <el-icon><UserFilled /></el-icon>
              <span class="user-name">{{ displayName }}</span>
              <el-icon class="chev"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="content-area">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ElMessage, ElMessageBox } from "element-plus"
import {
  Monitor, Connection, List, UserFilled, ArrowDown, SwitchButton, WarningFilled,
} from "@element-plus/icons-vue"
import { useAuth } from "@/composables/useAuth"
import { systemApi } from "@/api/system"
import type { SystemInfoVO } from "@/types"

const route = useRoute()
const router = useRouter()
const auth = useAuth()

/** 登录页走裸布局 */
const isBlank = computed(() => route.meta.blank === true)

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

const displayName = computed(() => auth.state.username || "未登录")
const runningTasks = computed(() => Number(systemInfo.value?.runningTasks ?? 0))

const currentTime = ref("")
const systemInfo = ref<SystemInfoVO | null>(null)
let timer: ReturnType<typeof setInterval> | null = null
let infoTimer: ReturnType<typeof setInterval> | null = null

function updateTime() {
  currentTime.value = new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false })
}

async function loadSystemInfo() {
  try {
    systemInfo.value = await systemApi.info(true)
  } catch {
    // 静默：系统信息拿不到不影响主流程
  }
}

async function onUserCommand(command: string) {
  if (command !== "logout") return
  try {
    await ElMessageBox.confirm("确定退出登录？退出后需要重新输入账号密码。", "退出登录", {
      type: "warning",
      confirmButtonText: "退出登录",
      cancelButtonText: "取消",
    })
  } catch {
    return
  }
  try {
    await auth.logout()
    ElMessage.success("已退出登录")
  } catch {
    ElMessage.warning("退出登录请求失败，已清理本地会话")
  }
  await router.replace({ path: "/login" })
}

onMounted(() => {
  updateTime()
  timer = setInterval(updateTime, 1000)
  loadSystemInfo()
  infoTimer = setInterval(loadSystemInfo, 30000)
})

/** 进入业务页时刷新一次系统信息（登录后 runningTasks 才有意义） */
watch(
  () => route.fullPath,
  () => {
    if (!isBlank.value) loadSystemInfo()
  },
)

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (infoTimer) clearInterval(infoTimer)
})
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

.sidebar-footer { padding: 12px 16px; border-top: 1px solid var(--border-subtle); display: flex; flex-direction: column; gap: 6px; }
.sidebar-status { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-muted); }
.status-indicator { width: 6px; height: 6px; border-radius: 50%; background: var(--accent-emerald); }
.status-indicator.warn { background: var(--accent-amber); }
.sidebar-meta { display: flex; align-items: center; gap: 5px; font-size: 11px; color: var(--text-muted); }
.meta-sep { opacity: 0.4; }
.sidebar-warn { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--accent-amber); }

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
.topbar-right { display: flex; align-items: center; gap: 10px; }
.topbar-time { font-size: 13px; color: var(--text-muted); letter-spacing: 0.04em; }

.user-chip {
  display: flex; align-items: center; gap: 6px;
  cursor: pointer; font-size: 13px; color: var(--text-secondary);
  padding: 4px 8px; border-radius: var(--radius-sm);
  transition: background var(--transition-fast);
  outline: none;
}
.user-chip:hover { background: var(--bg-secondary); }
.user-name { font-weight: 500; color: var(--text-primary); }
.chev { font-size: 12px; opacity: 0.5; }

.content-area { flex: 1; padding: 20px 28px; overflow-y: auto; }
</style>
