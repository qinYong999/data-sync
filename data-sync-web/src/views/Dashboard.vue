<template>
  <div>
    <!-- Animated stat cards -->
    <div class="stats-grid">
      <div
        v-for="(s, i) in stats"
        :key="s.label"
        class="stat-card data-edge card-lift fade-in-up"
        :class="`delay-${i + 1}`"
      >
        <div class="stat-card-body">
          <div class="stat-icon" :style="{ color: s.color }" v-html="s.icon"></div>
          <div class="stat-info">
            <div class="stat-value mono" :style="{ color: s.color }">
              {{ s.value }}
            </div>
            <div class="stat-label">{{ s.label }}</div>
          </div>
        </div>
        <div class="stat-trend" v-if="s.trend !== undefined">
          <span :class="s.trend >= 0 ? 'trend-up' : 'trend-down'">
            {{ s.trend >= 0 ? "↑" : "↓" }} {{ Math.abs(s.trend) }}%
          </span>
        </div>
      </div>
    </div>

    <!-- Recent failures -->
    <div class="section-header">
      <h3>最近失败记录</h3>
      <span class="section-badge" v-if="recentFailures.length">{{ recentFailures.length }}</span>
    </div>

    <div class="table-container fade-in-up delay-6">
      <el-table
        :data="recentFailures"
        v-loading="failsLoading"
        empty-text="暂无失败记录，一切正常 ✦"
        stripe
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="taskId" label="任务ID" width="80" />
        <el-table-column prop="startTime" label="时间" width="180" />
        <el-table-column prop="readRows" label="已读取" width="80">
          <template #default="{ row }"><span class="mono">{{ row.readRows }}</span></template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="error-text">{{ row.errorMessage || "—" }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from "vue"
import { dashboardApi } from "@/api/dashboard"
import type { DashboardVO, RecordVO } from "@/types"

const overview = ref<DashboardVO>({
  totalTasks: 0,
  runningTasks: 0,
  failedTasks: 0,
  successTasks: 0,
  totalRecords: 0,
  totalReadRows: 0,
})
const recentFailures = ref<RecordVO[]>([])
const failsLoading = ref(false)

let refreshTimer: ReturnType<typeof setInterval> | null = null

const stats = computed(() => [
  {
    label: "总任务数",
    value: overview.value.totalTasks,
    color: "#3b82f6",
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>`,
    trend: undefined,
  },
  {
    label: "运行中",
    value: overview.value.runningTasks,
    color: "#10b981",
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="9"/><polyline points="12,7 12,12 16,14"/></svg>`,
    trend: undefined,
  },
  {
    label: "失败次数",
    value: overview.value.failedTasks,
    color: "#f43f5e",
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="9"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`,
    trend: undefined,
  },
  {
    label: "成功次数",
    value: overview.value.successTasks,
    color: "#14d4b4",
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M22 12c0 5.52-4.48 10-10 10S2 17.52 2 12 6.48 2 12 2s10 4.48 10 10z"/><polyline points="8,12 11,15 16,9"/></svg>`,
    trend: undefined,
  },
  {
    label: "总执行次数",
    value: overview.value.totalRecords,
    color: "#8b5cf6",
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14,2 14,8 20,8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="16" y2="17"/><polyline points="10,9 9,9 8,9"/></svg>`,
    trend: undefined,
  },
  {
    label: "总读取行数",
    value: overview.value.totalReadRows.toLocaleString(),
    color: "#f59e0b",
    icon: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="22,12 18,12 15,21 9,3 6,12 2,12"/></svg>`,
    trend: undefined,
  },
])

async function loadDashboard() {
  try {
    overview.value = await dashboardApi.overview()
  } catch { /* interceptor handles toast */ }
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

onMounted(async () => {
  await Promise.all([loadDashboard(), loadFails()])
  refreshTimer = setInterval(loadDashboard, 30000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin-bottom: 40px;
}
.stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  cursor: default;
}
.stat-card-body {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.stat-icon {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  opacity: 0.6;
}
.stat-icon svg {
  width: 100%;
  height: 100%;
}
.stat-info {
  flex: 1;
  min-width: 0;
}
.stat-value {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
  margin-bottom: 2px;
}
.stat-label {
  font-size: 13px;
  color: var(--text-muted);
  font-weight: 400;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.section-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: var(--accent-rose-dim);
  color: var(--accent-rose);
  font-size: 11px;
  font-weight: 600;
  font-family: var(--font-code);
}

.table-container {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.error-text {
  color: var(--accent-rose);
  font-size: 13px;
}
</style>
