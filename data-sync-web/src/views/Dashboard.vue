<template>
  <div>
    <PageHeader>
      <template #title><h2>仪表盘</h2></template>
    </PageHeader>

    <div class="stats-grid">
      <div v-for="(s, i) in stats" :key="s.label" class="stat-card fade-in-up" :class="`delay-${i + 1}`">
        <div class="stat-value mono" :style="{ color: s.color }">{{ s.value }}</div>
        <div class="stat-label">{{ s.label }}</div>
      </div>
    </div>

    <h3 style="margin: 32px 0 12px">最近失败记录</h3>
    <div class="table-container fade-in-up delay-3">
      <el-table :data="recentFailures" v-loading="failsLoading" empty-text="暂无失败记录" stripe border style="width:100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="taskId" label="任务ID" width="70" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="已读取" width="80">
          <template #default="{ row }"><span class="mono">{{ row.readRows }}</span></template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="error-text">{{ row.errorMessage || "—" }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue"
import { dashboardApi } from "@/api/dashboard"
import PageHeader from "@/components/PageHeader.vue"
import type { DashboardVO, RecordVO } from "@/types"

const overview = ref<DashboardVO>({
  totalTasks: 0, runningTasks: 0, failedTasks: 0, successTasks: 0, totalRecords: 0, totalReadRows: 0,
})
const recentFailures = ref<RecordVO[]>([])
const failsLoading = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const stats = computed(() => {
  const o = overview.value
  return [
    { label: "总任务数", value: o.totalTasks, color: "#3b82f6" },
    { label: "运行中", value: o.runningTasks, color: "#10b981" },
    { label: "失败次数", value: o.failedTasks, color: "#f43f5e" },
    { label: "成功次数", value: o.successTasks, color: "#14d4b4" },
    { label: "总执行次数", value: o.totalRecords, color: "#8b5cf6" },
    { label: "总读取行数", value: o.totalReadRows.toLocaleString(), color: "#f59e0b" },
  ]
})


function formatTime(iso: string): string {
  if (!iso) return ""
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, "0")
  return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " + pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds())
}

async function loadDashboard() {
  try { overview.value = await dashboardApi.overview() } catch {}
}

async function loadFails() {
  failsLoading.value = true
  try { recentFailures.value = (await dashboardApi.recentFails()) || [] }
  catch { recentFailures.value = [] }
  finally { failsLoading.value = false }
}

onMounted(async () => {
  await Promise.all([loadDashboard(), loadFails()])
  timer = setInterval(loadDashboard, 30000)
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
.error-text { color: var(--accent-rose); font-size: 13px; }
</style>
