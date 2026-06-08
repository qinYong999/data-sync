<template>
  <div>
    <PageHeader>
      <template #title><h2>执行历史</h2></template>
      <template #actions>
        <el-button type="primary" @click="handleTrigger" :loading="executing">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" style="margin-right:4px"><polygon points="5,3 19,12 5,21"/></svg>
          手动执行
        </el-button>
      </template>
    </PageHeader>

    <!-- Live Log Terminal (on top) -->
    <div class="log-section fade-in-up delay-1">
      <div class="log-header">
        <div class="log-title">
          <span class="log-indicator" :class="{ active: connected }"></span>
          <span>实时日志</span>
          <el-tag v-if="connected" type="success" size="small" effect="dark">已连接</el-tag>
          <el-tag v-else type="info" size="small" effect="dark">未连接</el-tag>
        </div>
        <div class="log-actions">
          <el-button size="small" @click="clearLogs" :plain="true" :disabled="messages.length === 0">
            清空
          </el-button>
        </div>
      </div>
      <div class="log-panel" ref="logPanelRef">
        <div v-for="(msg, i) in messages" :key="i" class="log-line">{{ msg }}</div>
        <div v-if="messages.length === 0" class="log-line muted">等待日志消息...</div>
      </div>
    </div>

    <!-- Records Table (below log) -->
    <div class="table-container fade-in-up delay-2">
      <el-table
        :data="pagedRecords"
        v-loading="loading"
        empty-text="暂无执行记录"
        stripe
      >
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="处理量" width="160">
          <template #default="{ row }">
            <div class="process-stats">
              <span class="mono stat-read">{{ row.readRows }}</span>
              <span class="stat-arrow">&rarr;</span>
              <span class="mono stat-write">{{ row.writeRows }}</span>
              <span v-if="row.errorRows > 0" class="stat-errors mono">{{ row.errorRows }} err</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="触发" width="80">
          <template #default="{ row }"><StatusTag :value="row.triggerType" /></template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
            <span v-else class="no-error">&mdash;</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="records.length > pageSize"
        background
        layout="prev, pager, next, sizes, total"
        :total="records.length"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        class="pagination"
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

// Records
const records = ref<RecordVO[]>([])
const loading = ref(false)
const executing = ref(false)

// Pagination
const currentPage = ref(1)
const pageSize = ref(10)

const pagedRecords = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return records.value.slice(start, end)
})

// WebSocket
const logPanelRef = ref<HTMLElement>()
const scrollToBottom = () => {
  nextTick().then(() => {
    if (logPanelRef.value) logPanelRef.value.scrollTop = logPanelRef.value.scrollHeight
  })
}
const { connected, messages, connect, disconnect, clearMessages } = useWebSocket({ onMessage: scrollToBottom })
watch(messages, scrollToBottom)

function clearLogs() { clearMessages() }

async function loadRecords() {
  loading.value = true
  try {
    records.value = await taskApi.records(taskId)
    currentPage.value = 1
  } finally { loading.value = false }
}

async function handleTrigger() {
  executing.value = true
  try {
    const res = await taskApi.trigger(taskId)
    if (res.success) {
      ElMessage.success("任务已触发执行")
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
  } catch { executing.value = false }
}

onMounted(async () => {
  await loadRecords()
  connect()
})
</script>

<style scoped>
.table-container {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: 24px;
}
.process-stats {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.stat-read { color: var(--accent-blue); }
.stat-arrow { color: var(--text-muted); opacity: 0.4; font-size: 11px; }
.stat-write { color: var(--accent-emerald); }
.stat-errors { color: var(--accent-rose); font-size: 11px; }
.error-text {
  color: var(--accent-rose);
  font-size: 13px;
}
.no-error {
  color: var(--text-muted);
  opacity: 0.4;
}
.pagination {
  padding: 16px 20px;
  justify-content: flex-end;
  border-top: 1px solid var(--border-subtle);
}

/* Log Section */
.log-section {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: 20px;
}
.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-secondary);
}
.log-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
}
.log-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
  transition: all var(--transition-fast);
}
.log-indicator.active {
  background: var(--accent-emerald);
  box-shadow: 0 0 8px var(--accent-emerald-dim);
  animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
.log-actions {
  display: flex;
  gap: 6px;
}
</style>
