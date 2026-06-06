<template>
  <div>
    <h2>仪表盘</h2>
    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="8" v-for="s in stats" :key="s.label">
        <el-card><div class="stat-item">
          <div class="stat-value" :style="{ color: s.color }">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div></el-card>
      </el-col>
    </el-row>
    <h3 style="margin-top: 40px">最近失败记录</h3>
    <el-table :data="recentFails" border stripe style="margin-top: 10px">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="taskId" label="任务ID" width="80" />
      <el-table-column prop="startTime" label="时间" width="180" />
      <el-table-column prop="errorMessage" label="错误信息" min-width="300" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { dashboardApi } from "@/api/dashboard"
const overview = ref({ totalTasks: 0, runningTasks: 0, failedTasks: 0, successTasks: 0, totalRecords: 0, totalReadRows: 0 })
const recentFails = ref<any[]>([])
const stats = computed(() => [
  { label: "总任务数", value: overview.value.totalTasks, color: "#409eff" },
  { label: "运行中", value: overview.value.runningTasks, color: "#67c23a" },
  { label: "失败次数", value: overview.value.failedTasks, color: "#f56c6c" },
  { label: "成功次数", value: overview.value.successTasks, color: "#67c23a" },
  { label: "总执行次数", value: overview.value.totalRecords, color: "#909399" },
  { label: "总读取行数", value: overview.value.totalReadRows.toLocaleString(), color: "#e6a23c" },
])
onMounted(async () => { overview.value = await dashboardApi.overview(); recentFails.value = await dashboardApi.recentFails() })
</script>
<style scoped>
.stat-item { text-align: center; padding: 10px 0; }
.stat-value { font-size: 36px; font-weight: bold; }
.stat-label { font-size: 14px; color: #999; margin-top: 8px; }
</style>