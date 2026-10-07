<template>
  <div>
    <PageHeader>
      <template #title><h2>仪表盘</h2></template>
      <template #actions>
        <el-button @click="loadAll" :loading="refreshing" :plain="true">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </template>
    </PageHeader>

    <div class="stats-grid">
      <div v-for="(s, i) in stats" :key="s.label" class="stat-card fade-in-up" :class="`delay-${i + 1}`">
        <div class="stat-value mono" :style="{ color: s.color }">{{ s.value }}</div>
        <div class="stat-label">{{ s.label }}</div>
      </div>
    </div>

    <el-alert
      v-if="overviewError"
      type="warning"
      :closable="false"
      show-icon
      title="概览统计加载失败"
      :description="overviewError"
      class="section-gap"
    />

    <!-- 系统信息 -->
    <h3 class="section-title">系统信息</h3>
    <div class="table-container fade-in-up delay-2">
      <div class="sysinfo">
        <div v-if="systemLoading" class="sysinfo-loading">
          <el-icon class="is-loading"><Loading /></el-icon><span>加载中…</span>
        </div>
        <template v-else-if="systemInfo">
          <div class="sysinfo-item"><span class="sysinfo-label">版本</span><span class="mono">{{ orDash(systemInfo.version) }}</span></div>
          <div class="sysinfo-item"><span class="sysinfo-label">元数据库</span><span>{{ orDash(systemInfo.dbType) }}</span></div>
          <div class="sysinfo-item"><span class="sysinfo-label">Java</span><span class="mono">{{ orDash(systemInfo.javaVersion) }}</span></div>
          <div class="sysinfo-item"><span class="sysinfo-label">启动时间</span><span class="mono">{{ formatTime(systemInfo.startTime) || "—" }}</span></div>
          <div class="sysinfo-item"><span class="sysinfo-label">任务总数</span><span class="mono">{{ formatCount(systemInfo.totalTasks) }}</span></div>
          <div class="sysinfo-item"><span class="sysinfo-label">运行中任务</span><span class="mono">{{ formatCount(systemInfo.runningTasks) }}</span></div>
          <div class="sysinfo-item"><span class="sysinfo-label">历史记录数</span><span class="mono">{{ formatCount(systemInfo.totalRecords) }}</span></div>
          <div class="sysinfo-item">
            <span class="sysinfo-label">认证</span>
            <el-tag :type="systemInfo.securityEnabled === false ? 'warning' : 'success'" size="small" effect="plain">
              {{ systemInfo.securityEnabled === false ? "未启用" : "已启用（表单登录 + CSRF）" }}
            </el-tag>
          </div>
          <div class="sysinfo-item">
            <span class="sysinfo-label">达梦 DM8</span>
            <el-tag :type="systemInfo.dm8Verified ? 'success' : 'warning'" size="small" effect="plain">
              {{ systemInfo.dm8Verified ? "已验证" : "未在真实实例验证" }}
            </el-tag>
          </div>
          <div v-if="poolText" class="sysinfo-item wide">
            <span class="sysinfo-label">连接池</span>
            <span class="mono pool-text">{{ poolText }}</span>
          </div>
        </template>
        <div v-else class="sysinfo-empty">
          <el-icon><InfoFilled /></el-icon>
          <span>无法获取系统信息（GET /api/system/info）。请确认后端已启动且登录会话有效。</span>
        </div>
      </div>
    </div>

    <h3 class="section-title">最近失败记录</h3>
    <div class="table-container fade-in-up delay-3">
      <el-table :data="recentFailures" v-loading="failsLoading" stripe border style="width:100%">
        <template #empty>
          <EmptyState :visible="true" icon="✓" description="没有失败记录，一切正常。" />
        </template>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="taskId" label="任务ID" width="80" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }"><span class="mono time-cell">{{ formatTime(row.startTime) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="86">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="已读取" width="90">
          <template #default="{ row }"><span class="mono">{{ formatCount(row.readRows) }}</span></template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="280">
          <template #default="{ row }">
            <CopyText :value="row.errorMessage" placeholder="—" />
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue"
import { Refresh, Loading, InfoFilled } from "@element-plus/icons-vue"
import { dashboardApi } from "@/api/dashboard"
import { systemApi, describePoolSummary } from "@/api/system"
import { toApiError } from "@/api/request"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import EmptyState from "@/components/EmptyState.vue"
import CopyText from "@/components/CopyText.vue"
import { formatCount, formatTime, orDash } from "@/utils/format"
import type { DashboardVO, RecordVO, SystemInfoVO } from "@/types"

const overview = ref<DashboardVO>({
  totalTasks: 0, runningTasks: 0, failedTasks: 0, successTasks: 0, totalRecords: 0, totalReadRows: 0,
})
const recentFailures = ref<RecordVO[]>([])
const failsLoading = ref(false)
const refreshing = ref(false)
const overviewError = ref("")

const systemInfo = ref<SystemInfoVO | null>(null)
const systemLoading = ref(false)

let timer: ReturnType<typeof setInterval> | null = null

const stats = computed(() => {
  const o = overview.value
  return [
    { label: "总任务数", value: formatCount(o.totalTasks), color: "#3b82f6" },
    { label: "运行中", value: formatCount(o.runningTasks), color: "#10b981" },
    { label: "失败次数", value: formatCount(o.failedTasks), color: "#f43f5e" },
    { label: "成功次数", value: formatCount(o.successTasks), color: "#14d4b4" },
    { label: "总执行次数", value: formatCount(o.totalRecords), color: "#8b5cf6" },
    { label: "总读取行数", value: formatCount(o.totalReadRows), color: "#f59e0b" },
  ]
})

const poolText = computed(() => describePoolSummary(systemInfo.value?.poolSummary))

async function loadOverview() {
  try {
    overview.value = await dashboardApi.overview()
    overviewError.value = ""
  } catch (e) {
    overviewError.value = toApiError(e).message
  }
}

async function loadFails() {
  failsLoading.value = true
  try {
    recentFailures.value = (await dashboardApi.recentFails()) || []
  } catch {
    recentFailures.value = []
  } finally {
    failsLoading.value = false
  }
}

async function loadSystem() {
  systemLoading.value = true
  try {
    systemInfo.value = await systemApi.info(true)
  } catch {
    systemInfo.value = null
  } finally {
    systemLoading.value = false
  }
}

async function loadAll() {
  refreshing.value = true
  try {
    await Promise.all([loadOverview(), loadFails(), loadSystem()])
  } finally {
    refreshing.value = false
  }
}

onMounted(async () => {
  await loadAll()
  timer = setInterval(loadOverview, 30000)
})

onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
@media (max-width: 1000px) { .stats-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 600px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }

.stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 16px;
  text-align: center;
}
.stat-value { font-size: 24px; font-weight: 700; line-height: 1.1; margin-bottom: 4px; }
.stat-label { font-size: 13px; color: var(--text-muted); }

.section-title { margin: 28px 0 12px; }
.section-gap { margin-top: 16px; }

.sysinfo {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr));
  gap: 12px 20px;
  padding: 18px 20px;
}
@media (max-width: 1000px) { .sysinfo { grid-template-columns: repeat(2, 1fr); } }
.sysinfo-item { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.sysinfo-item.wide { grid-column: 1 / -1; }
.sysinfo-label { font-size: 11px; color: var(--text-muted); }
.pool-text { font-size: 12px; line-height: 1.7; word-break: break-word; }
.sysinfo-loading, .sysinfo-empty {
  grid-column: 1 / -1;
  display: flex; align-items: center; gap: 6px;
  color: var(--text-muted); font-size: 13px;
}
.time-cell { font-size: 12px; color: var(--text-secondary); }
</style>
