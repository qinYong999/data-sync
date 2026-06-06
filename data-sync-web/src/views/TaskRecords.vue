<template>
  <div>
    <h2>执行历史</h2>
    <el-table :data="records" border stripe v-loading="loading" style="margin-bottom:20px">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="startTime" label="开始时间" width="180" />
      <el-table-column prop="endTime" label="结束时间" width="180" />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status==='SUCCESS'?'success':row.status==='FAILED'?'danger':row.status==='RUNNING'?'warning':'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="readRows" label="读取行" width="80" />
      <el-table-column prop="writeRows" label="写入行" width="80" />
      <el-table-column prop="errorRows" label="失败行" width="80" />
      <el-table-column prop="triggerType" label="触发方式" width="100" />
      <el-table-column prop="errorMessage" label="错误信息" min-width="200" show-overflow-tooltip />
    </el-table>

    <h3>实时日志 <el-tag v-if="connected" type="success" size="small">已连接</el-tag><el-tag v-else type="danger" size="small">未连接</el-tag></h3>
    <div class="log-panel" ref="logPanel">
      <div v-for="(msg, i) in logMessages" :key="i" class="log-line">{{ msg }}</div>
      <div v-if="logMessages.length === 0" class="log-line muted">等待日志消息...</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from "vue"
import { useRoute } from "vue-router"
import { taskApi } from "@/api/task"
import { createLogWebSocket } from "@/api/websocket"

const route = useRoute()
const records = ref<any[]>([])
const loading = ref(false)
const logMessages = ref<string[]>([])
const connected = ref(false)
const logPanel = ref<HTMLElement>()
let ws: WebSocket | null = null

const scrollToBottom = async () => { await nextTick(); if (logPanel.value) logPanel.value.scrollTop = logPanel.value.scrollHeight }

onMounted(async () => {
  loading.value = true
  try { records.value = await taskApi.records(Number(route.params.id)) } finally { loading.value = false }
  ws = createLogWebSocket((msg) => {
    logMessages.value.push(msg)
    connected.value = true
    scrollToBottom()
  })
})

onUnmounted(() => { if (ws) ws.close() })

watch(logMessages, scrollToBottom)
</script>

<style scoped>
.log-panel { background: #1e1e1e; color: #d4d4d4; font-family: "Courier New", monospace; font-size: 13px; padding: 12px; border-radius: 4px; height: 300px; overflow-y: auto; }
.log-line { padding: 2px 0; line-height: 1.5; white-space: pre-wrap; word-break: break-all; }
.log-line.muted { color: #666; }
</style>