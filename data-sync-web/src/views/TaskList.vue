<template>
  <div>
    <PageHeader>
      <template #title><h2>同步任务</h2></template>
      <template #actions>
        <el-button type="primary" @click="$router.push('/tasks/new')">
          <el-icon><Plus /></el-icon>新增任务
        </el-button>
      </template>
    </PageHeader>

    <div class="table-container fade-in-up delay-1">
      <el-table :data="data" v-loading="loading" empty-text="暂无同步任务，请先创建一个" stripe>
        <el-table-column prop="id" label="ID" width="55" />
        <el-table-column prop="name" label="任务名称" min-width="130" />
        <el-table-column label="源" min-width="160">
          <template #default="{ row }">
            <span class="table-path">
              <span class="mono ds">{{ dsNameMap[row.sourceDsId] || "DS:" + row.sourceDsId }}</span>
              <span class="sep">.</span>
              <span class="mono tbl">{{ row.sourceTable }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="160">
          <template #default="{ row }">
            <span class="table-path">
              <span class="mono ds">{{ dsNameMap[row.targetDsId] || "DS:" + row.targetDsId }}</span>
              <span class="sep">.</span>
              <span class="mono tbl">{{ row.targetTable }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模式" width="100">
          <template #default="{ row }"><StatusTag :value="row.syncMode" /></template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><StatusTag :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="调度" width="100">
          <template #default="{ row }"><span class="mono cron">{{ row.cronExpression || "—" }}</span></template>
        </el-table-column>
        <el-table-column label="操作" min-width="220">
          <template #default="{ row }">
            <div class="actions">
              <el-button size="small" type="primary" @click="handleTrigger(row)" :plain="true">执行</el-button>
              <el-button size="small" :type="row.status === 'ENABLED' ? 'warning' : 'success'" @click="toggleStatus(row)" :plain="true">
                {{ row.status === "ENABLED" ? "禁用" : "启用" }}
              </el-button>
              <el-button size="small" @click="$router.push('/tasks/' + row.id + '/edit')">编辑</el-button>
              <el-button size="small" @click="$router.push('/tasks/' + row.id + '/records')">历史</el-button>
              <el-button size="small" type="danger" @click="handleDelete(row)" :plain="true">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue"
import { Plus } from "@element-plus/icons-vue"
import { taskApi } from "@/api/task"
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"
import { useConfirm } from "@/composables/useConfirm"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import type { TaskVO, DataSourceVO } from "@/types"

const data = ref<TaskVO[]>([])
const loading = ref(false)
const dsNameMap = ref<Record<number, string>>({})
const confirm = useConfirm()

async function load() {
  loading.value = true
  try {
    const res = await taskApi.list({ page: 0, size: 999, sort: "id,desc" })
    data.value = res.content || []
  } finally { loading.value = false }
}

async function loadDsNames() {
  try {
    const res = await datasourceApi.list({ page: 0, size: 999 })
    const map: Record<number, string> = {}
    ;(res.content || []).forEach((ds: DataSourceVO) => { map[ds.id] = ds.name })
    dsNameMap.value = map
  } catch {}
}

async function toggleStatus(row: TaskVO) {
  try {
    if (row.status === "ENABLED") { await taskApi.disable(row.id); ElMessage.success("已禁用") }
    else { await taskApi.enable(row.id); ElMessage.success("已启用") }
    await load()
  } catch {}
}

async function handleTrigger(row: TaskVO) {
  try {
    const res = await taskApi.trigger(row.id)
    ElMessage.success(res.success ? "任务已触发执行" : "触发失败：" + (res.message || ""))
  } catch {}
}

async function handleDelete(row: TaskVO) {
  if (!(await confirm(`确定删除任务 "${row.name}"？`, "提示"))) return
  try { await taskApi.delete(row.id); ElMessage.success("已删除"); await load() } catch {}
}

onMounted(async () => { await Promise.all([load(), loadDsNames()]) })
</script>

<style scoped>
.table-path { display: flex; align-items: center; gap: 3px; font-size: 13px; }
.ds { color: var(--accent); font-size: 12px; }
.tbl { color: var(--text-primary); }
.sep { color: var(--text-muted); opacity: 0.3; }
.cron { color: var(--text-muted); font-size: 12px; }
.actions { display: flex; gap: 3px; flex-wrap: nowrap; }
</style>
