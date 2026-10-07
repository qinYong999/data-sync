<template>
  <div>
    <PageHeader>
      <template #title>
        <h2>执行历史 <span class="task-id mono">#{{ taskId }}</span></h2>
      </template>
      <template #actions>
        <el-button @click="openPreflight" :plain="true">
          <el-icon><MagicStick /></el-icon>预检
        </el-button>
        <el-button @click="reloadAll" :loading="loading" :plain="true">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button
          v-if="runningRecord"
          type="warning"
          @click="handleCancel"
          :loading="cancelling"
          :plain="true"
        >
          <el-icon><CircleClose /></el-icon>取消执行
        </el-button>
        <el-button
          v-else
          type="primary"
          @click="handleTrigger"
          :loading="executing || preflightChecking"
        >
          <el-icon><VideoPlay /></el-icon>{{ preflightChecking ? "预检中…" : "手动执行" }}
        </el-button>
      </template>
    </PageHeader>

    <!-- 运行中进度条 -->
    <div v-if="runningRecord" class="run-banner fade-in-up">
      <div class="run-banner-left">
        <span class="run-dot"></span>
        <span class="run-title">任务正在运行</span>
        <span class="run-meta mono">
          读取 {{ formatCount(latestStat.readRows) }} · 写入 {{ formatCount(latestStat.writeRows) }}
          <template v-if="latestStat.skippedRows"> · 跳过 {{ formatCount(latestStat.skippedRows) }}</template>
        </span>
      </div>
      <span class="run-hint">取消是协作式的：当前批次写完后停止，水位不推进</span>
    </div>

    <!-- Log Terminal -->
    <div class="log-section fade-in-up delay-1">
      <div class="log-header">
        <div class="log-title">
          <span class="log-indicator" :class="{ active: connected, reconnecting }"></span>
          <span>实时日志</span>
          <el-tag :type="connected ? 'success' : reconnecting ? 'warning' : 'info'" size="small" effect="dark">
            {{ statusText }}
          </el-tag>
          <span class="log-task-hint">仅本任务（{{ taskId }}）</span>
        </div>
        <div class="log-actions">
          <el-button
            v-if="!connected"
            size="small"
            @click="retryNow"
            :plain="true"
          >立即重连</el-button>
          <el-button size="small" @click="clearMessages" :plain="true" :disabled="messages.length === 0">清空</el-button>
        </div>
      </div>
      <div class="log-panel" ref="logPanelRef">
        <div v-for="(msg, i) in messages" :key="i" class="log-line">{{ msg }}</div>
        <div v-if="messages.length === 0" class="log-line muted">
          {{ connected ? "等待日志消息…（任务执行时才会输出）" : "日志连接未建立，正在尝试重连…" }}
        </div>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      title="执行记录加载失败"
      :description="loadError"
      class="fade-in-up"
    />

    <!-- Records Table -->
    <div class="table-container fade-in-up delay-2">
      <el-table
        :data="rows"
        v-loading="loading"
        stripe
        border
        style="width:100%"
        empty-text="暂无执行记录"
        row-key="id"
      >
        <template #empty>
          <EmptyState :visible="true" icon="◈" description="该任务还没有执行记录。点击右上角「手动执行」开始第一次同步。" />
        </template>

        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail-grid">
              <div class="detail-item">
                <span class="detail-label">读取耗时</span>
                <span class="mono">{{ formatMillis(row.readMillis) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">写入耗时</span>
                <span class="mono">{{ formatMillis(row.writeMillis) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">总耗时</span>
                <span class="mono">{{ formatMillis(row.totalMillis) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">跳过行数</span>
                <span class="mono">{{ formatCount(row.skippedRows) }}</span>
              </div>
              <div class="detail-item wide">
                <span class="detail-label">起始游标（startCursor）</span>
                <span class="mono break">{{ orDash(row.startCursor) }}</span>
              </div>
              <div class="detail-item wide">
                <span class="detail-label">结束游标（endCursor，仅成功推进）</span>
                <span class="mono break">{{ orDash(row.endCursor) }}</span>
              </div>
              <div v-if="row.runKey" class="detail-item wide">
                <span class="detail-label">runKey</span>
                <span class="mono break">{{ row.runKey }}</span>
              </div>
              <div v-if="row.errorMessage" class="detail-item wide">
                <span class="detail-label">错误信息</span>
                <CopyText :value="row.errorMessage" block />
              </div>
              <div v-if="row.preflightJson" class="detail-item wide">
                <span class="detail-label">预检结果快照</span>
                <CopyText :value="row.preflightJson" block />
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="id" label="#" width="58" />
        <el-table-column label="状态" width="86">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="开始 / 结束" width="170">
          <template #default="{ row }">
            <div class="time-cell">
              <el-tooltip :content="formatTime(row.startTime)" placement="top" :show-after="300">
                <span class="mono">{{ shortTime(row.startTime) }}</span>
              </el-tooltip>
              <el-tooltip :content="formatTime(row.endTime) || '尚未结束'" placement="top" :show-after="300">
                <span class="mono time-end">{{ shortTime(row.endTime) || "—" }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="处理量" width="158">
          <template #default="{ row }">
            <span class="mono nowrap read">{{ formatCount(row.readRows) }}</span>
            <span class="arrow">&rarr;</span>
            <span class="mono nowrap write">{{ formatCount(row.writeRows) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="跳过" width="62" align="center">
          <template #default="{ row }">
            <span v-if="Number(row.skippedRows) > 0" class="mono skip">{{ formatCount(row.skippedRows) }}</span>
            <span v-else class="no-err">—</span>
          </template>
        </el-table-column>
        <el-table-column label="游标推进" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.startCursor || row.endCursor">
              <span class="mono cursor-cell">
                {{ shortCursor(row.startCursor) }}
                <span class="arrow">&rarr;</span>
                <span :class="{ 'cursor-ok': !!row.endCursor }">{{ shortCursor(row.endCursor) }}</span>
              </span>
            </template>
            <span v-else class="no-err">—</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="88" align="right">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.totalMillis"
              placement="top"
              :content="`读 ${formatMillis(row.readMillis)} / 写 ${formatMillis(row.writeMillis)} / 总 ${formatMillis(row.totalMillis)}`"
            >
              <span class="mono duration-cell">{{ formatMillis(row.totalMillis) }}</span>
            </el-tooltip>
            <span v-else class="no-err">—</span>
          </template>
        </el-table-column>
        <el-table-column label="触发" width="72">
          <template #default="{ row }"><StatusTag :value="row.triggerType" /></template>
        </el-table-column>
        <el-table-column label="执行结果" min-width="170">
          <template #default="{ row }">
            <CopyText
              v-if="row.status === 'FAILED'"
              :value="row.errorMessage || '执行失败（后端未返回原因）'"
            />
            <span v-else-if="isSuccessStatus(row.status)" class="summary-text">
              读取 <span class="mono emph">{{ formatCount(row.readRows) }}</span> →
              写入 <span class="mono emph">{{ formatCount(row.writeRows) }}</span>
              <span v-if="Number(row.skippedRows) > 0" class="diff">，跳过 {{ formatCount(row.skippedRows) }} 行</span>
            </span>
            <span v-else-if="isRunningStatus(row.status)" class="summary-text running-text">执行中…</span>
            <span v-else-if="row.status === 'CANCELLED' || row.status === 'CANCELED'" class="summary-text cancel-text">
              已取消，水位未推进（下次从 startCursor 重跑）
            </span>
            <span v-else class="no-err">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="112" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :disabled="errorCountOf(row) === 0"
              @click="openErrors(row)"
              :plain="true"
            >坏行明细{{ errorCountOf(row) > 0 ? "(" + errorCountOf(row) + ")" : "" }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > 0"
        background
        layout="prev,pager,next,sizes,total"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        v-model:current-page="page"
        v-model:page-size="size"
        class="pagination"
      />
    </div>

    <RecordErrorsDialog v-model="errorsVisible" :record="errorsRecord" />
    <PreflightDialog v-model="preflightVisible" :task-id="taskId" :task-name="taskName" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from "vue"
import { useRoute } from "vue-router"
import {
  Refresh, MagicStick, VideoPlay, CircleClose,
} from "@element-plus/icons-vue"
import { ElMessage } from "element-plus"
import { taskApi } from "@/api/task"
import { recordApi } from "@/api/record"
import { toApiError } from "@/api/request"
import { useWebSocket } from "@/composables/useWebSocket"
import { useConfirm } from "@/composables/useConfirm"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import EmptyState from "@/components/EmptyState.vue"
import CopyText from "@/components/CopyText.vue"
import RecordErrorsDialog from "@/components/RecordErrorsDialog.vue"
import PreflightDialog from "@/components/PreflightDialog.vue"
import { formatCount, formatMillis, formatTime, orDash } from "@/utils/format"
import { parseLogMessage, belongsToTask } from "@/utils/wsMessage"
import { blockReason, preflightHasError } from "@/utils/preflight"
import { isRunningStatus, isSuccessStatus } from "@/types"
import type { PreflightRes, RecordVO } from "@/types"

const route = useRoute()
const taskId = Number(route.params.id)
const taskName = ref("")
const confirm = useConfirm()

const rows = ref<RecordVO[]>([])
const allRecords = ref<RecordVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const clientMode = ref(false)
const loading = ref(false)
const loadError = ref("")

const runningRecord = ref<RecordVO | null>(null)
const executing = ref(false)
const cancelling = ref(false)

const errorsVisible = ref(false)
const errorsRecord = ref<RecordVO | null>(null)
const preflightVisible = ref(false)
const preflightChecking = ref(false)
const preflightResult = ref<PreflightRes | null>(null)

let pollTimer: ReturnType<typeof setInterval> | null = null

// ---------- 日志 ----------

const logPanelRef = ref<HTMLElement>()
function scrollToBottom() {
  nextTick().then(() => {
    if (logPanelRef.value) logPanelRef.value.scrollTop = logPanelRef.value.scrollHeight
  })
}

const {
  connected, statusText, reconnecting, messages, connect, clearMessages, retryNow,
} = useWebSocket({
  taskId,
  onMessage: () => scrollToBottom(),
  // 服务端已按 ?taskId= 过滤；此处再做一次兜底，避免切换到其它任务时串台
  filter: (m) => belongsToTask(parseLogMessage(m), taskId),
})

watch(messages, scrollToBottom)

// ---------- 数据加载 ----------

const latestStat = computed(() => {
  const r = runningRecord.value
  return {
    readRows: Number(r?.readRows ?? 0),
    writeRows: Number(r?.writeRows ?? 0),
    skippedRows: Number(r?.skippedRows ?? 0),
  }
})

function sliceClient() {
  const start = (page.value - 1) * size.value
  rows.value = allRecords.value.slice(start, start + size.value)
}

async function loadRecords() {
  loading.value = true
  loadError.value = ""
  try {
    if (clientMode.value) {
      sliceClient()
      return
    }
    const res = await recordApi.list(taskId, page.value - 1, size.value)
    if (res.clientPaged) {
      clientMode.value = true
      allRecords.value = res.all || []
      total.value = res.totalElements
      sliceClient()
    } else {
      rows.value = res.content
      total.value = res.totalElements
    }
  } catch (e) {
    const err = toApiError(e)
    loadError.value = err.message
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 只取最新一条，用于判断任务是否仍在运行 */
async function refreshRunningState() {
  try {
    const res = await recordApi.list(taskId, 0, 1)
    const latest = res.content?.[0] || null
    runningRecord.value = latest && isRunningStatus(latest.status) ? latest : null
  } catch {
    // 忽略：不改变已有状态
  }
  syncPolling()
}

async function reloadAll() {
  await Promise.all([loadRecords(), refreshRunningState()])
}

function syncPolling() {
  if (runningRecord.value && !pollTimer) {
    pollTimer = setInterval(async () => {
      await refreshRunningState()
      if (page.value === 1 && !clientMode.value) await loadRecords()
    }, 2500)
  } else if (!runningRecord.value && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function errorCountOf(row: RecordVO): number {
  return Number(row.errorRows ?? 0) + Number(row.skippedRows ?? 0)
}

function shortCursor(v?: string | null): string {
  if (!v) return "—"
  const s = String(v)
  return s.length > 18 ? s.slice(0, 8) + "…" + s.slice(-6) : s
}

/** 时间列用的紧凑格式：MM-DD HH:mm:ss（完整时间放 tooltip） */
function shortTime(v?: string | null): string {
  const full = formatTime(v)
  if (!full) return ""
  return full.length >= 19 ? full.slice(5) : full
}

function openErrors(row: RecordVO) {
  errorsRecord.value = row
  errorsVisible.value = true
}

// ---------- 操作 ----------

function openPreflight() {
  preflightVisible.value = true
}

async function handleTrigger() {
  // 契约 D5：预检强制执行。先在界面上跑一次，有 ERROR 直接阻止并说明原因。
  preflightChecking.value = true
  try {
    preflightResult.value = await taskApi.preflight(taskId)
  } catch {
    preflightResult.value = null // 预检接口不可用时不阻断（后端执行前仍会强制预检）
  } finally {
    preflightChecking.value = false
  }

  if (preflightResult.value && preflightHasError(preflightResult.value)) {
    preflightVisible.value = true
    ElMessage.error(blockReason(preflightResult.value) || "预检未通过，已阻止执行")
    return
  }

  executing.value = true
  try {
    const res = await taskApi.trigger(taskId)
    if (res.success) {
      ElMessage.success(res.message || "任务已触发执行")
      runningRecord.value = { id: 0, taskId, startTime: new Date().toISOString(), status: "RUNNING", totalRows: 0, readRows: 0, writeRows: 0, errorRows: 0, triggerType: "MANUAL" }
      syncPolling()
      await refreshRunningState()
      await loadRecords()
    } else {
      ElMessage.error(res.message || "触发失败")
    }
  } catch (e) {
    ElMessage.error(toApiError(e).message)
  } finally {
    executing.value = false
  }
}

async function handleCancel() {
  const ok = await confirm(
    "确定取消这次同步？取消是协作式的：当前批次写完后停止，游标（水位）不会推进，下次执行会从原水位重跑。",
    "取消执行",
  )
  if (!ok) return

  cancelling.value = true
  try {
    const res = await taskApi.cancel(taskId)
    if (res.success) ElMessage.success(res.message || "已请求取消任务")
    else ElMessage.error(res.message || "取消失败")
  } catch (e) {
    const err = toApiError(e)
    if (err.code === "TASK_NOT_RUNNING" || err.status === 409) {
      ElMessage.warning(err.message || "任务当前未在运行")
    } else {
      ElMessage.error(err.message)
    }
  } finally {
    cancelling.value = false
    await refreshRunningState()
    await loadRecords()
  }
}

watch([page, size], () => {
  loadRecords()
})

onMounted(async () => {
  try {
    const t = await taskApi.get(taskId)
    taskName.value = t?.name || ""
  } catch {
    /* 名称仅用于标题展示 */
  }
  await reloadAll()
  connect()
})

onUnmounted(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
})
</script>

<style scoped>
.task-id { font-size: 13px; color: var(--text-muted); font-weight: 400; margin-left: 6px; }

.run-banner {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 10px 14px; margin-bottom: 14px;
  border: 1px solid rgba(217, 119, 6, 0.3);
  background: rgba(217, 119, 6, 0.07);
  border-radius: var(--radius-md);
  flex-wrap: wrap;
}
.run-banner-left { display: flex; align-items: center; gap: 8px; }
.run-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--accent-amber);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.25; } }
.run-title { font-weight: 600; font-size: 13px; color: #b45309; }
.run-meta { font-size: 12px; color: var(--text-secondary); }
.run-hint { font-size: 12px; color: var(--text-muted); }

.log-section {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: 16px;
}
.log-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 14px; border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-secondary);
}
.log-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; }
.log-indicator { width: 7px; height: 7px; border-radius: 50%; background: var(--text-muted); }
.log-indicator.active { background: var(--accent-emerald); }
.log-indicator.reconnecting { background: var(--accent-amber); }
.log-task-hint { font-size: 12px; color: var(--text-muted); }
.log-actions { display: flex; gap: 6px; }

.table-container { margin-bottom: 24px; }
.read { color: var(--accent-blue); }
.write { color: var(--accent-emerald); }
.skip { color: var(--accent-amber); }
.arrow { color: var(--text-muted); margin: 0 4px; opacity: 0.35; font-size: 11px; }
.error-text { color: var(--accent-rose); font-size: 13px; }
.summary-text { color: var(--text-secondary); font-size: 13px; }
.summary-text .emph { color: var(--text-primary); }
.summary-text .diff { color: var(--accent-amber); font-size: 12px; }
.running-text { color: var(--accent-amber); }
.cancel-text { color: var(--text-muted); font-size: 12px; }
.no-err { color: var(--text-muted); opacity: 0.4; }
.time-cell { display: flex; flex-direction: column; line-height: 1.5; font-size: 12px; }
.time-cell .time-end { color: var(--text-muted); }
.nowrap { white-space: nowrap; }
.cursor-cell { font-size: 12px; white-space: nowrap; }
.cursor-ok { color: var(--accent-emerald); }
.duration-cell { font-size: 12px; white-space: nowrap; }

.detail-grid {
  display: grid; grid-template-columns: repeat(4, minmax(140px, 1fr));
  gap: 10px 18px; padding: 12px 18px;
}
.detail-item { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.detail-item.wide { grid-column: 1 / -1; }
.detail-label { font-size: 11px; color: var(--text-muted); }
.break { word-break: break-all; white-space: pre-wrap; }
:deep(.el-alert) { margin-bottom: 12px; }
</style>
