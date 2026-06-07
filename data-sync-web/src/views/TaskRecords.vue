<template>
  <div>
    <PageHeader>
      <template #title><h2>执行历史</h2></template>
      <template #actions>
        <el-button type="primary" @click="handleTrigger" :loading="executing">
          手动执行
        </el-button>
      </template>
    </PageHeader>

    <el-table
      :data="records"
      border
      stripe
      v-loading="loading"
      style="margin-bottom: 20px"
      empty-text="暂无执行记录"
    >
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="startTime" label="开始时间" width="180" />
      <el-table-column prop="endTime" label="结束时间" width="180" />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }"><StatusTag :value="row.status" /></template>
      </el-table-column>
      <el-table-column prop="readRows" label="读取行" width="80" />
      <el-table-column prop="writeRows" label="写入行" width="80" />
      <el-table-column prop="errorRows" label="失败行" width="80" />
      <el-table-column prop="triggerType" label="触发方式" width="100">
        <template #default="{ row }"><StatusTag :value="row.triggerType" /></template>
      </el-table-column>
      <el-table-column
        prop="errorMessage"
        label="错误信息"
        min-width="200"
        show-overflow-tooltip
      />
    </el-table>

    <h3>
      实时日志
      <el-tag v-if="connected" type="success" size="small">已连接</el-tag>
      <el-tag v-else type="danger" size="small">未连接</el-tag>
    </h3>
    <div class="log-panel" ref="logPanelRef">
      <div v-for="(msg, i) in messages" :key="i" class="log-line">{{ msg }}</div>
      <div v-if="messages.length === 0" class="log-line muted">等待日志消息...</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from "vue"
import { useRoute } from "vue-router"
import { taskApi } from "@/api/task"
import { ElMessage } from "element-plus"
import { useWebSocket } from "@/composables/useWebSocket"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import type { RecordVO } from "@/types"

const route = useRoute()
const taskId = Number(route.params.id)

const records = ref<RecordVO[]>([])
const loading = ref(false)
const executing = ref(false)
const logPanelRef = ref<HTMLElement>()

// WebSocket
const { connected, messages, connect, disconnect } = useWebSocket({
  onMessage: () => scrollToBottom(),
})

const scrollToBottom = async () => {
  await nextTick()
  if (logPanelRef.value) {
    logPanelRef.value.scrollTop = logPanelRef.value.scrollHeight
  }
}

watch(messages, scrollToBottom)

async function loadRecords() {
  loading.value = true
  try {
    records.value = await taskApi.records(taskId)
  } finally {
    loading.value = false
  }
}

async function handleTrigger() {
  executing.value = true
  try {
    const res = await taskApi.trigger(taskId)
    if (res.success) {
      ElMessage.success("任务已触发执行")
      // 轮询等待执行完成，刷新记录
      const poll = setInterval(async () => {
        await loadRecords()
        const latest = records.value[0]
        if (latest && latest.status !== "RUNNING") {
          clearInterval(poll)
          executing.value = false
        }
      }, 2000)
    } else {
      ElMessage.error("触发失败: " + (res.message || ""))
      executing.value = false
    }
  } catch {
    executing.value = false
  }
}

onMounted(async () => {
  await loadRecords()
  connect()
})
</script>
