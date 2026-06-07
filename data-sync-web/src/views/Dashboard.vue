<template>
  <div>
    <PageHeader>
      <template #title><h2>仪表盘</h2></template>
    </PageHeader>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="8" v-for="s in stats" :key="s.label">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-value" :style="{ color: s.color }">
              {{ s.loading ? "—" : s.value }}
            </div>
            <div class="stat-label">{{ s.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <h3 style="margin-top: 40px">最近失败记录</h3>
    <el-table
      :data="recentFailures"
      border
      stripe
      v-loading="failsLoading"
      style="margin-top: 10px"
      empty-text="暂无失败记录"
    >
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="taskId" label="任务ID" width="80" />
      <el-table-column prop="startTime" label="时间" width="180" />
      <el-table-column prop="readRows" label="已读取" width="80" />
      <el-table-column prop="errorMessage" label="错误信息" min-width="300" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from "vue"
import { dashboardApi } from "@/api/dashboard"
import PageHeader from "@/components/PageHeader.vue"
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
  { label: "总任务数", value: overview.value.totalTasks, color: "#409eff", loading: false },
  { label: "运行中", value: overview.value.runningTasks, color: "#67c23a", loading: false },
  { label: "失败次数", value: overview.value.failedTasks, color: "#f56c6c", loading: false },
  { label: "成功次数", value: overview.value.successTasks, color: "#67c23a", loading: false },
  { label: "总执行次数", value: overview.value.totalRecords, color: "#909399", loading: false },
  {
    label: "总读取行数",
    value: overview.value.totalReadRows.toLocaleString(),
    color: "#e6a23c",
    loading: false,
  },
])

async function loadDashboard() {
  try {
    overview.value = await dashboardApi.overview()
  } catch {
    // interceptor handles toast
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

onMounted(async () => {
  await Promise.all([loadDashboard(), loadFails()])
  refreshTimer = setInterval(loadDashboard, 30000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>
