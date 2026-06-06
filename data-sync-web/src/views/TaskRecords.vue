<template>
  <div>
    <h2>????</h2>
    <el-table :data="records" border stripe v-loading="loading" style="margin-bottom:20px">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="startTime" label="????" width="180" />
      <el-table-column prop="endTime" label="????" width="180" />
      <el-table-column prop="status" label="??" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status==='SUCCESS'?'success':row.status==='FAILED'?'danger':row.status==='RUNNING'?'warning':'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="readRows" label="???" width="80" />
      <el-table-column prop="writeRows" label="???" width="80" />
      <el-table-column prop="errorRows" label="???" width="80" />
      <el-table-column prop="triggerType" label="????" width="100" />
      <el-table-column prop="errorMessage" label="????" min-width="200" show-overflow-tooltip />
    </el-table>

    <h3>???? <el-tag v-if="connected" type="success" size="small">???</el-tag><el-tag v-else type="danger" size="small">???</el-tag></h3>
    <div class="log-panel" ref="logPanel">
      <div v-for="(msg, i) in logMessages" :key="i" class="log-line">{{ msg }}</div>
      <div v-if="logMessages.length === 0" class="log-line muted">??????...</div>
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
