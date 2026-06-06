<template>
  <div>
    <h2>执行历史</h2>
    <el-table :data="records" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="startTime" label="开始时间" width="180" />
      <el-table-column prop="endTime" label="结束时间" width="180" />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }"><el-tag :type="row.status==='SUCCESS'?'success':row.status==='FAILED'?'danger':row.status==='RUNNING'?'warning':'info'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="readRows" label="读取行" width="80" />
      <el-table-column prop="writeRows" label="写入行" width="80" />
      <el-table-column prop="errorRows" label="失败行" width="80" />
      <el-table-column prop="triggerType" label="触发方式" width="100" />
      <el-table-column prop="errorMessage" label="错误信息" min-width="200" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue"
import { useRoute } from "vue-router"
import { taskApi } from "@/api/task"
const route = useRoute(); const records = ref<any[]>([]); const loading = ref(false)
onMounted(async () => { loading.value = true; try { records.value = await taskApi.records(Number(route.params.id)) } finally { loading.value = false } })
</script>