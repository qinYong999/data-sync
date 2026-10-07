<template>
  <div>
    <PageHeader>
      <template #title><h2>同步任务</h2></template>
      <template #actions>
        <el-button @click="handlePreflightAll" :loading="preflightAllLoading" :plain="true">
          <el-icon><MagicStick /></el-icon>全部预检
        </el-button>
        <el-button @click="reload" :loading="loading" :plain="true">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="$router.push('/tasks/new')">
          <el-icon><Plus /></el-icon>新增任务
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="runningCount > 0"
      type="warning"
      :closable="false"
      show-icon
      :title="`当前有 ${runningCount} 个任务正在运行`"
      description="运行中的任务可以点击「取消」协作式停止（当前批次结束后退出，水位不推进）。"
      class="fade-in-up"
    />

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      title="任务列表加载失败"
      :description="error"
      class="fade-in-up"
    />

    <div class="table-container fade-in-up delay-1">
      <el-table
        :data="data"
        v-loading="loading"
        stripe
        border
        style="width:100%"
        empty-text="暂无同步任务"
        @row-click="goToRecords"
      >
        <template #empty>
          <EmptyState :visible="true" icon="◈" description="还没有同步任务。创建任务并预检通过后即可执行同步。">
            <el-button type="primary" size="small" @click="$router.push('/tasks/new')">
              <el-icon><Plus /></el-icon>新增任务
            </el-button>
          </EmptyState>
        </template>

        <el-table-column prop="id" label="ID" width="50" />
        <el-table-column prop="name" label="任务名称" min-width="110" />
        <el-table-column label="源" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="table-path">
              <span class="mono ds">{{ dsNameMap[row.sourceDsId] || "DS:" + row.sourceDsId }}</span>
              <span class="sep">.</span>
              <span class="mono tbl">{{ row.sourceTable }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="table-path">
              <span class="mono ds">{{ dsNameMap[row.targetDsId] || "DS:" + row.targetDsId }}</span>
              <span class="sep">.</span>
              <span class="mono tbl">{{ row.targetTable }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模式" width="120">
          <template #default="{ row }"><StatusTag :value="row.syncMode" /></template>
        </el-table-column>
        <el-table-column label="运行状态" width="96">
          <template #default="{ row }">
            <StatusTag v-if="isRunning(row)" value="RUNNING" />
            <span v-else class="idle-text">空闲</span>
          </template>
        </el-table-column>
        <el-table-column label="预检" width="104">
          <template #default="{ row }">
            <span v-if="preflightLoading[row.id]" class="pf-state loading">
              <el-icon class="is-loading"><Loading /></el-icon>检查中
            </span>
            <el-tooltip
              v-else-if="preflightMap[row.id] && preflightHasError(preflightMap[row.id]!)"
              :content="blockReason(preflightMap[row.id])"
              placement="top"
            >
              <span class="pf-state error"><el-icon><CircleCloseFilled /></el-icon>有错误</span>
            </el-tooltip>
            <span v-else-if="preflightMap[row.id]" class="pf-state" :class="preflightMap[row.id]!.issues.length ? 'warn' : 'ok'">
              <el-icon><component :is="preflightMap[row.id]!.issues.length ? WarningFilled : CircleCheckFilled" /></el-icon>
              {{ preflightMap[row.id]!.issues.length ? preflightMap[row.id]!.issues.length + " 警告" : "通过" }}
            </span>
            <span v-else class="pf-state idle">未预检</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="86">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="调度" width="110">
          <template #default="{ row }"><span class="mono cron">{{ row.cronExpression || "—" }}</span></template>
        </el-table-column>
        <el-table-column label="操作" min-width="330">
          <template #default="{ row }">
            <div class="actions">
              <el-button
                v-if="isRunning(row)"
                size="small"
                type="warning"
                @click="handleCancel(row)"
                :loading="cancellingId === row.id"
                :plain="true"
              >取消</el-button>
              <el-tooltip
                v-else
                :disabled="!executeBlocked(row)"
                :content="blockReason(preflightMap[row.id]) || '预检未通过，请先修复问题'"
                placement="top"
              >
                <span>
                  <el-button
                    size="small"
                    type="primary"
                    @click="handleTrigger(row)"
                    :loading="triggeringId === row.id"
                    :disabled="executeBlocked(row)"
                    :plain="true"
                  >执行</el-button>
                </span>
              </el-tooltip>
              <el-button size="small" @click="openPreflight(row)" :plain="true">预检</el-button>
              <el-button size="small" :type="row.status === 'ENABLED' ? 'warning' : 'success'" @click="toggleStatus(row)" :plain="true">{{ row.status === "ENABLED" ? "禁用" : "启用" }}</el-button>
              <el-button size="small" @click="$router.push('/tasks/' + row.id + '/edit')">编辑</el-button>
              <el-button size="small" @click="$router.push('/tasks/' + row.id + '/records')">历史</el-button>
              <el-button size="small" type="danger" @click="handleDelete(row)" :plain="true">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-pagination
      v-if="total > 0"
      background
      layout="prev,pager,next,sizes,total"
      :total="total"
      :page-sizes="sizes"
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
    />

    <PreflightDialog
      v-model="preflightDialogVisible"
      :task-id="preflightTaskId"
      :task-name="preflightTaskName"
      @result="onPreflightResult"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from "vue"
import { useRouter } from "vue-router"
import {
  Plus, Refresh, MagicStick, Loading, CircleCheckFilled, CircleCloseFilled, WarningFilled,
} from "@element-plus/icons-vue"
import { taskApi } from "@/api/task"
import { datasourceApi } from "@/api/datasource"
import { recordApi } from "@/api/record"
import { toApiError } from "@/api/request"
import { ElMessage } from "element-plus"
import { usePagination } from "@/composables/usePagination"
import { useConfirm } from "@/composables/useConfirm"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import EmptyState from "@/components/EmptyState.vue"
import PreflightDialog from "@/components/PreflightDialog.vue"
import { runWithConcurrency } from "@/utils/async"
import { blockReason, preflightHasError } from "@/utils/preflight"
import { isRunningStatus } from "@/types"
import type { PreflightRes, TaskVO } from "@/types"

const router = useRouter()
const confirm = useConfirm()

const { data, loading, total, page, size, sizes, error, load } =
  usePagination<TaskVO>((p, s) => taskApi.list({ page: p, size: s, sort: "id,desc" }))

const dsNameMap = ref<Record<number, string>>({})

/** 预检结果缓存：id → 结果（undefined = 本次会话尚未预检） */
const preflightMap = reactive<Record<number, PreflightRes | null>>({})
const preflightLoading = reactive<Record<number, boolean>>({})
const preflightAllLoading = ref(false)

/** 运行状态：以"最新一条执行记录的状态"为准 */
const runningMap = reactive<Record<number, boolean>>({})
const triggeringId = ref<number | null>(null)
const cancellingId = ref<number | null>(null)

const preflightDialogVisible = ref(false)
const preflightTaskId = ref<number | null>(null)
const preflightTaskName = ref("")

const runningCount = computed(() => data.value.filter((r) => isRunning(r)).length)

let pollTimer: ReturnType<typeof setInterval> | null = null

function goToRecords(row: TaskVO, _column: unknown, event: Event) {
  const target = event?.target as HTMLElement | null
  if (target && target.closest("button, .el-button, .el-checkbox, .el-switch")) return
  router.push("/tasks/" + row.id + "/records")
}

function serverSaysRunning(row: TaskVO | undefined): boolean {
  if (!row) return false
  if (row.running === true) return true
  return isRunningStatus(row.runStatus as string | undefined)
}

function isRunning(row: TaskVO): boolean {
  if (!row) return false
  return serverSaysRunning(row) || runningMap[row.id] === true
}

/** 预检结果是 ERROR → 禁止执行 */
function executeBlocked(row: TaskVO): boolean {
  const pf = preflightMap[row.id]
  return !!pf && preflightHasError(pf)
}

async function loadList() {
  const ok = await load()
  if (ok) await refreshRunning()
}

async function reload() {
  await loadList()
  await loadDsNames()
}

async function loadDsNames() {
  try {
    const res = await datasourceApi.list({ page: 0, size: 999 })
    const map: Record<number, string> = {}
    ;(res.content || []).forEach((ds) => { map[ds.id] = ds.name })
    dsNameMap.value = map
  } catch {
    /* 名称映射失败不影响主流程 */
  }
}

/**
 * 刷新运行状态：默认取每个任务最新一条执行记录的状态。
 * 用受限并发，避免一次打太多请求。
 */
async function refreshRunning(rows: TaskVO[] = data.value) {
  await runWithConcurrency(
    rows,
    async (row) => {
      if (serverSaysRunning(row)) {
        runningMap[row.id] = true
        return
      }
      try {
        const res = await recordApi.list(row.id, 0, 1)
        const latest = res.content?.[0]
        runningMap[row.id] = isRunningStatus(latest?.status as string | undefined)
      } catch {
        // 拿不到就不改（保持上一次已知状态）
      }
    },
    { limit: 3 },
  )
  syncPolling()
}

function syncPolling() {
  const anyRunning = data.value.some((r) => isRunning(r))
  if (anyRunning && !pollTimer) {
    pollTimer = setInterval(() => { refreshRunning() }, 4000)
  } else if (!anyRunning && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ---------- 预检 ----------

function openPreflight(row: TaskVO) {
  preflightTaskId.value = row.id
  preflightTaskName.value = row.name
  preflightDialogVisible.value = true
}

function onPreflightResult(taskId: number, res: PreflightRes) {
  preflightMap[taskId] = res
}

async function runPreflight(row: TaskVO): Promise<PreflightRes | null> {
  preflightLoading[row.id] = true
  try {
    const res = await taskApi.preflight(row.id)
    preflightMap[row.id] = res
    return res
  } catch {
    // 接口不可用时不做拦截（后端执行前仍会强制预检）
    return null
  } finally {
    preflightLoading[row.id] = false
  }
}

async function handlePreflightAll() {
  const rows = data.value
  if (rows.length === 0) { ElMessage.info("当前页没有任务"); return }
  preflightAllLoading.value = true
  let errors = 0
  try {
    await runWithConcurrency(
      rows,
      async (row) => {
        const res = await runPreflight(row)
        if (res && preflightHasError(res)) errors++
      },
      { limit: 3 },
    )
    if (errors > 0) ElMessage.warning(`全部预检完成：${errors} 个任务存在阻断性错误`)
    else ElMessage.success("全部预检完成：均未发现阻断性错误")
  } finally {
    preflightAllLoading.value = false
  }
}

// ---------- 执行 / 取消 ----------

async function handleTrigger(row: TaskVO) {
  // 首次点击先跑预检；有 ERROR 则中止并说明原因（契约 D5：预检强制执行）
  let pf = preflightMap[row.id]
  if (pf === undefined) {
    triggeringId.value = row.id
    pf = await runPreflight(row)
  }

  if (pf && preflightHasError(pf)) {
    openPreflight(row)
    ElMessage.error(blockReason(pf) || "预检未通过，已阻止执行")
    triggeringId.value = null
    return
  }

  triggeringId.value = row.id
  try {
    const res = await taskApi.trigger(row.id)
    if (res.success) {
      ElMessage.success(res.message || "任务已触发执行")
      runningMap[row.id] = true
      syncPolling()
    } else {
      ElMessage.error(res.message || "触发失败")
      if (String((res as unknown as { code?: string }).code || "") === "TASK_RUNNING") {
        runningMap[row.id] = true
        syncPolling()
      }
    }
  } catch (e) {
    const err = toApiError(e)
    if (err.code === "TASK_RUNNING") {
      ElMessage.warning(err.message || "任务正在执行中")
      runningMap[row.id] = true
      syncPolling()
    } else {
      ElMessage.error(err.message)
    }
  } finally {
    triggeringId.value = null
  }
}

async function handleCancel(row: TaskVO) {
  const ok = await confirm(
    `确定取消正在运行的任务 "${row.name}"？\n取消是协作式的：当前批次写完后停止，游标（水位）不会推进，下次执行会从原水位重跑。`,
    "取消任务",
  )
  if (!ok) return

  cancellingId.value = row.id
  try {
    const res = await taskApi.cancel(row.id)
    if (res.success) {
      ElMessage.success(res.message || "已请求取消任务")
      await refreshRunning()
    } else {
      ElMessage.error(res.message || "取消失败")
    }
  } catch (e) {
    const err = toApiError(e)
    if (err.code === "TASK_NOT_RUNNING" || err.status === 409) {
      ElMessage.warning(err.message || "任务当前未在运行，无需取消")
      runningMap[row.id] = false
      await refreshRunning()
    } else {
      ElMessage.error(err.message)
    }
  } finally {
    cancellingId.value = null
  }
}

// ---------- 其它操作 ----------

async function toggleStatus(row: TaskVO) {
  try {
    if (row.status === "ENABLED") { await taskApi.disable(row.id); ElMessage.success("已禁用") }
    else { await taskApi.enable(row.id); ElMessage.success("已启用") }
    await load()
  } catch {
    /* 全局拦截器已提示 */
  }
}

async function handleDelete(row: TaskVO) {
  if (!(await confirm(`确定删除任务 "${row.name}"？该任务的历史执行记录会一并删除。`, "删除确认"))) return
  try {
    await taskApi.delete(row.id)
    ElMessage.success("已删除")
    delete preflightMap[row.id]
    delete runningMap[row.id]
    await load()
  } catch {
    /* 全局拦截器已提示 */
  }
}

/** 换页后清掉上一页的运行态缓存，避免错位 */
watch(page, () => {
  syncPolling()
})

onMounted(async () => {
  await Promise.all([loadList(), loadDsNames()])
})

onUnmounted(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
})
</script>

<style scoped>
.table-path { display: flex; align-items: center; gap: 3px; font-size: 13px; white-space: nowrap; overflow: hidden; }
.table-path .ds, .table-path .tbl { overflow: hidden; text-overflow: ellipsis; }
.ds { color: var(--accent); font-size: 12px; }
.tbl { color: var(--text-primary); }
.sep { color: var(--text-muted); opacity: 0.3; }
.cron { color: var(--text-muted); font-size: 12px; }
.actions { display: flex; gap: 3px; flex-wrap: wrap; }
.idle-text { font-size: 12px; color: var(--text-muted); }
.pf-state { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; }
.pf-state.ok { color: #047857; }
.pf-state.warn { color: var(--accent-amber); }
.pf-state.error { color: #bc1c3e; }
.pf-state.idle { color: var(--text-muted); }
.pf-state.loading { color: var(--text-secondary); }
:deep(.el-alert) { margin-bottom: 12px; }
</style>
