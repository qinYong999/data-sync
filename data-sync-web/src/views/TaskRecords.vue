<template>
  <div>
    <PageHeader>
      <template #title><h2>执行历史</h2></template>
      <template #actions>
        <el-button type="primary" @click="handleTrigger" :loading="executing">手动执行</el-button>
      </template>
    </PageHeader>

    <!-- Log Terminal -->
    <div class="log-section fade-in-up delay-1">
      <div class="log-header">
        <div class="log-title">
          <span class="log-indicator" :class="{ active: connected }"></span>
          <span>实时日志</span>
          <el-tag v-if="connected" type="success" size="small" effect="dark">已连接</el-tag>
          <el-tag v-else type="info" size="small" effect="dark">未连接</el-tag>
        </div>
        <el-button size="small" @click="clearLogs" :plain="true" :disabled="messages.length === 0">清空</el-button>
      </div>
      <div class="log-panel" ref="logPanelRef">
        <div v-for="(msg, i) in messages" :key="i" class="log-line">{{ msg }}</div>
        <div v-if="messages.length === 0" class="log-line muted">等待日志消息...</div>
      </div>
    </div>

    <!-- Records Table -->
    <div class="table-container fade-in-up delay-2">
      <el-table :data="pagedRecords" v-loading="loading" empty-text="暂无执行记录" stripe>
        <el-table-column prop="id" label="#" width="55" />
        <el-table-column prop="startTime" label="开始时间" width="160" />
        <el-table-column prop="endTime" label="结束时间" width="160" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="处理量" width="140">
          <template #default="{ row }">
            <span class="mono read">{{ row.readRows }}</span>
            <span class="arrow">→</span>
            <span class="mono write">{{ row.writeRows }}</span>
            <span v-if="row.errorRows > 0" class="mono err">+{{ row.errorRows }}err</span>
          </template>
        </el-table-column>
        <el-table-column label="触发" width="70">
          <template #default="{ row }"><StatusTag :value="row.triggerType" /></template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
            <span v-else class="no-err">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="records.length > pageSize"
        background layout="prev,pager,next,sizes,total"
        :total="records.length" v-model:current-page="currentPage" v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]" class="pagination"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from "vue"
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
const currentPage = ref(1)
const pageSize = ref(10)

const pagedRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return records.value.slice(start, start + pageSize.value)
})

const logPanelRef = ref<HTMLElement>()
const scrollToBottom = () => { nextTick().then(() => { if (logPanelRef.value) logPanelRef.value.scrollTop = logPanelRef.value.scrollHeight }) }
const { connected, messages, connect, clearMessages } = useWebSocket({ onMessage: scrollToBottom })
watch(messages, scrollToBottom)

function clearLogs() { clearMessages() }

async function loadRecords() {
  loading.value = true
  try { records.value = await taskApi.records(taskId); currentPage.value = 1 } finally { loading.value = false }
}

async function handleTrigger() {
  executing.value = true
  try {
    const res = await taskApi.trigger(taskId)
    if (res.success) {
      ElMessage.success("任务已触发执行")
      const poll = setInterval(async () => {
        await loadRecords()
        if (records.value[0] && records.value[0].status !== "RUNNING") { clearInterval(poll); executing.value = false }
      }, 2000)
    } else { ElMessage.error("触发失败: " + (res.message || "")); executing.value = false }
  } catch { executing.value = false }
}

onMounted(async () => { await loadRecords(); connect() })
</script>

<style scoped>
.log-section {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: 20px;
}
.log-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px; border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-secondary);
}
.log-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; }
.log-indicator {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--text-muted);
}
.log-indicator.active { background: var(--accent-emerald); }

.table-container { margin-bottom: 24px; }
.read { color: var(--accent-blue); }
.write { color: var(--accent-emerald); }
.err { color: var(--accent-rose); font-size: 11px; margin-left: 4px; }
.arrow { color: var(--text-muted); margin: 0 4px; opacity: 0.3; }
.error-text { color: var(--accent-rose); font-size: 13px; }
.no-err { color: var(--text-muted); opacity: 0.35; }
</style>
