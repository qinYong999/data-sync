<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="900px"
    top="6vh"
    append-to-body
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="onOpen"
  >
    <div class="errors-toolbar">
      <div class="errors-meta">
        <template v-if="record">
          <span>记录 #{{ record.id }}</span>
          <span class="sep">·</span>
          <span>任务 #{{ record.taskId }}</span>
          <span class="sep">·</span>
          <StatusTag :value="record.status" />
          <template v-if="skippedCount > 0">
            <span class="sep">·</span>
            <span class="warn">跳过 {{ formatCount(skippedCount) }} 行</span>
          </template>
        </template>
      </div>
      <div class="errors-filters">
        <el-select v-model="phase" size="small" style="width: 150px" @change="onFilterChange">
          <el-option label="全部阶段" :value="PHASE_ALL" />
          <el-option v-for="p in phaseOptions" :key="p" :label="ERROR_PHASE_MAP[p] || p" :value="p" />
        </el-select>
        <el-button size="small" :loading="loading" @click="reload" :plain="true">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
      class="errors-alert"
    />

    <el-table
      :data="rows"
      v-loading="loading"
      :empty-text="loading ? '加载中…' : '没有坏行明细（该次执行未跳过任何行）'"
      stripe
      border
      size="small"
      style="width: 100%"
      max-height="52vh"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="row-detail">
            <div class="row-detail-title">源行数据（截断至 4000 字符）</div>
            <CopyText :value="row.rowData" block placeholder="（未记录行数据）" />
            <div class="row-detail-meta">
              <span v-if="row.id !== undefined">明细 ID：{{ row.id }}</span>
              <span v-if="row.recordId !== undefined">· 记录 ID：{{ row.recordId }}</span>
              <span v-if="row.createdAt">· 时间：{{ formatTime(row.createdAt) }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="阶段" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="phaseTagType(row.phase)" effect="plain">
            {{ ERROR_PHASE_MAP[row.phase] || row.phase || "—" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="行主键" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono">{{ row.rowKey || "—" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="错误信息" min-width="300">
        <template #default="{ row }">
          <CopyText :value="row.message" placeholder="（无消息）" />
        </template>
      </el-table-column>
      <el-table-column label="可重试" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.retryable" type="success" size="small" effect="plain">是</el-tag>
          <el-tag v-else type="info" size="small" effect="plain">否</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="165">
        <template #default="{ row }">
          <span class="mono time-cell">{{ formatTime(row.createdAt) || "—" }}</span>
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

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from "vue"
import { Refresh } from "@element-plus/icons-vue"
import { recordApi } from "@/api/record"
import { toApiError } from "@/api/request"
import StatusTag from "@/components/StatusTag.vue"
import CopyText from "@/components/CopyText.vue"
import { formatCount, formatTime } from "@/utils/format"
import { ERROR_PHASE_MAP } from "@/types"
import type { RecordVO, SyncErrorVO } from "@/types"

const props = defineProps<{
  modelValue: boolean
  record: RecordVO | null
}>()

const emit = defineEmits<{ "update:modelValue": [v: boolean] }>()

const loading = ref(false)
const rows = ref<SyncErrorVO[]>([])
const all = ref<SyncErrorVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
/**
 * 阶段过滤。用哨兵值而不是空字符串：el-select 把 '' 视为"未选择"从而显示 placeholder（英文 Select），
 * 拿不到中文的"全部阶段"。
 */
const PHASE_ALL = '__ALL__'
const phase = ref<string>(PHASE_ALL)
const clientMode = ref(false)
const loadError = ref("")

const phaseOptions = ["PREFLIGHT", "READ", "MAP", "WRITE"]

const skippedCount = computed(() => Number(props.record?.skippedRows ?? 0))

const title = computed(() =>
  props.record ? `坏行明细 — 执行记录 #${props.record.id}` : "坏行明细",
)

function phaseTagType(p?: string): "success" | "warning" | "danger" | "info" {
  if (p === "PREFLIGHT") return "warning"
  if (p === "WRITE") return "danger"
  if (p === "READ") return "info"
  return "info"
}

function sliceClient() {
  const start = (page.value - 1) * size.value
  rows.value = all.value.slice(start, start + size.value)
}

async function load() {
  const recordId = props.record?.id
  if (!recordId) {
    rows.value = []
    total.value = 0
    return
  }
  loading.value = true
  loadError.value = ""
  try {
    if (clientMode.value) {
      sliceClient()
      return
    }
    const activePhase = phase.value === PHASE_ALL ? undefined : phase.value
    const res = await recordApi.errors(recordId, page.value - 1, size.value, activePhase)
    if (res.clientPaged) {
      clientMode.value = true
      all.value = res.all || []
      total.value = res.totalElements
      sliceClient()
    } else {
      rows.value = res.content
      total.value = res.totalElements
    }
  } catch (e) {
    const err = toApiError(e)
    loadError.value = err.status === 404 ? "后端暂未提供坏行明细接口（GET /api/records/{id}/errors）" : err.message
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function reload() {
  clientMode.value = false
  all.value = []
  load()
}

function onFilterChange() {
  page.value = 1
  reload()
}

function onOpen() {
  const pageChanged = page.value !== 1
  phase.value = PHASE_ALL
  clientMode.value = false
  all.value = []
  page.value = 1
  // page 变化时由 watcher 触发加载，避免重复请求
  if (!pageChanged) load()
}

watch([page, size], () => {
  load()
})
</script>

<style scoped>
.errors-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; margin-bottom: 12px; flex-wrap: wrap;
}
.errors-meta { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-secondary); flex-wrap: wrap; }
.errors-meta .sep { opacity: 0.35; }
.errors-meta .warn { color: var(--accent-amber); }
.errors-filters { display: flex; align-items: center; gap: 8px; }

.errors-alert { margin-bottom: 12px; }

.row-detail { padding: 10px 14px; display: flex; flex-direction: column; gap: 8px; }
.row-detail-title { font-size: 12px; color: var(--text-muted); font-weight: 500; }
.row-detail-meta { font-size: 12px; color: var(--text-muted); }

.time-cell { font-size: 12px; color: var(--text-secondary); }

.pagination { margin-top: 12px; border-top: none; }
</style>
