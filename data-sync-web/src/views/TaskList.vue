<template>
  <div>
    <PageHeader>
      <template #title><h2>同步任务</h2></template>
      <template #actions>
        <el-button type="primary" @click="$router.push('/tasks/new')">新增任务</el-button>
      </template>
    </PageHeader>

    <el-table :data="data" border stripe v-loading="loading" empty-text="暂无同步任务">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="任务名称" min-width="150" />
      <el-table-column label="源库" width="200">
        <template #default="{ row }">
          {{ dsNameMap[row.sourceDsId] ?? row.sourceDsId }}
          .{{ row.sourceTable }}
        </template>
      </el-table-column>
      <el-table-column label="目标库" width="200">
        <template #default="{ row }">
          {{ dsNameMap[row.targetDsId] ?? row.targetDsId }}
          .{{ row.targetTable }}
        </template>
      </el-table-column>
      <el-table-column prop="syncMode" label="模式" width="110">
        <template #default="{ row }"><StatusTag :value="row.syncMode" /></template>
      </el-table-column>
      <el-table-column prop="cronExpression" label="调度表达式" width="150" />
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }"><StatusTag :value="row.status" /></template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :type="row.status === 'ENABLED' ? 'warning' : 'success'"
            @click="toggleStatus(row)"
          >
            {{ row.status === "ENABLED" ? "禁用" : "启用" }}
          </el-button>
          <el-button size="small" type="primary" @click="handleTrigger(row)">手动执行</el-button>
          <el-button size="small" @click="$router.push('/tasks/' + row.id + '/edit')">编辑</el-button>
          <el-button size="small" @click="$router.push('/tasks/' + row.id + '/records')">历史</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue"
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

async function loadTasks() {
  loading.value = true
  try {
    const res = await taskApi.list({ page: 0, size: 999, sort: "id,desc" })
    data.value = res.content || []
  } finally {
    loading.value = false
  }
}

async function loadDataSourceNames() {
  try {
    const res = await datasourceApi.list({ page: 0, size: 999 })
    const map: Record<number, string> = {}
    ;(res.content || []).forEach((ds: DataSourceVO) => {
      map[ds.id] = ds.name
    })
    dsNameMap.value = map
  } catch {
    // non-blocking
  }
}

async function toggleStatus(row: TaskVO) {
  try {
    if (row.status === "ENABLED") {
      await taskApi.disable(row.id)
      ElMessage.success("已禁用")
    } else {
      await taskApi.enable(row.id)
      ElMessage.success("已启用")
    }
    await loadTasks()
  } catch {
    // interceptor handles toast
  }
}

async function handleTrigger(row: TaskVO) {
  try {
    const res = await taskApi.trigger(row.id)
    ElMessage.success(res.success ? "任务已触发执行" : "触发失败：" + (res.message || ""))
  } catch {
    // interceptor handles toast
  }
}

async function handleDelete(row: TaskVO) {
  if (!(await confirm(`确定删除任务 "${row.name}"？`, "提示"))) return
  try {
    await taskApi.delete(row.id)
    ElMessage.success("已删除")
    await loadTasks()
  } catch {
    // interceptor handles toast
  }
}

onMounted(async () => {
  await Promise.all([loadTasks(), loadDataSourceNames()])
})
</script>
