<template>
  <div>
    <PageHeader>
      <template #title><h2>{{ isEdit ? "编辑同步任务" : "新增同步任务" }}</h2></template>
    </PageHeader>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="140px"
      style="max-width: 700px"
    >
      <el-form-item label="任务名称" prop="name" required>
        <el-input v-model="form.name" />
      </el-form-item>

      <el-form-item label="源数据源" prop="sourceDsId" required>
        <el-select v-model="form.sourceDsId" style="width: 100%">
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="源表名" prop="sourceTable" required>
        <el-select
          v-model="form.sourceTable"
          filterable
          clearable
          placeholder="请先选择源数据源"
          style="width: 100%"
        >
          <el-option v-for="t in sourceTables" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>

      <el-form-item label="目标数据源" prop="targetDsId" required>
        <el-select v-model="form.targetDsId" style="width: 100%">
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="目标表名" prop="targetTable" required>
        <el-select
          v-model="form.targetTable"
          filterable
          clearable
          placeholder="请先选择目标数据源"
          style="width: 100%"
        >
          <el-option v-for="t in targetTables" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>

      <el-form-item label="同步模式" prop="syncMode" required>
        <el-select v-model="form.syncMode">
          <el-option label="全量同步" value="FULL" />
          <el-option label="增量同步" value="INCR" />
          <el-option label="先全量后增量" value="FULL_INCR" />
        </el-select>
      </el-form-item>

      <el-form-item label="增量字段" v-if="form.syncMode !== 'FULL'">
        <el-input v-model="form.incrColumn" placeholder="updated_at" />
      </el-form-item>

      <el-form-item label="Cron 表达式">
        <el-input v-model="form.cronExpression" placeholder="0 */5 * * * ?" />
      </el-form-item>

      <el-form-item label="每页行数">
        <el-input-number v-model="form.pageSize" :min="100" :max="10000" :step="100" />
      </el-form-item>

      <el-form-item label="批量写入">
        <el-input-number v-model="form.batchSize" :min="100" :max="5000" :step="100" />
      </el-form-item>
    </el-form>

    <el-form label-width="140px" style="max-width: 700px; margin-top: 20px">
      <el-form-item label="字段映射">
        <FieldMappingEditor
          v-if="mappingTaskId"
          :task-id="mappingTaskId"
          @update:mappings="onMappingsUpdate"
        />
        <el-alert
          v-else
          title="请先保存任务后再配置字段映射"
          type="info"
          :closable="false"
          show-icon
        />
      </el-form-item>
    </el-form>

    <div style="margin-top: 20px; padding-left: 140px">
      <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
      <el-button @click="$router.back()">取消</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, reactive } from "vue"
import { useRoute, useRouter } from "vue-router"
import { taskApi } from "@/api/task"
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"
import PageHeader from "@/components/PageHeader.vue"
import FieldMappingEditor from "@/views/FieldMappingEditor.vue"
import type { TaskForm as TaskFormType } from "@/types"

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const formRef = ref<any>(null)
const saving = ref(false)

const rules = {
  name: [{ required: true, message: "请输入任务名称", trigger: "blur" }],
  sourceDsId: [{ required: true, message: "请选择源数据源", trigger: "change" }],
  targetDsId: [{ required: true, message: "请选择目标数据源", trigger: "change" }],
  sourceTable: [{ required: true, message: "请选择源表", trigger: "change" }],
  targetTable: [{ required: true, message: "请选择目标表", trigger: "change" }],
  syncMode: [{ required: true, message: "请选择同步模式", trigger: "change" }],
}

const form = reactive<TaskFormType>({
  name: "",
  sourceDsId: null,
  targetDsId: null,
  sourceTable: "",
  targetTable: "",
  syncMode: "FULL_INCR",
  incrColumn: "",
  incrValue: "",
  cronExpression: "",
  pageSize: 1000,
  batchSize: 500,
})

const sourceTables = ref<string[]>([])
const targetTables = ref<string[]>([])
const datasources = ref<any[]>([])
const savedTaskId = ref<number | null>(null)
const mappingTaskId = ref<number | null>(null)

// 字段映射 — FieldMappingEditor 仅在该 ref 变化时重新渲染
const fieldMappings = ref<any[]>([])

function onMappingsUpdate(mappings: any[]) {
  fieldMappings.value = mappings
}

onMounted(async () => {
  // 加载数据源列表
  try {
    const res: any = await datasourceApi.list({ page: 0, size: 999, sort: "id,desc" })
    datasources.value = res.content || []
  } catch {
    // interceptor handles toast
  }

  // 编辑模式：加载任务数据回显
  if (isEdit.value) {
    try {
      const task = await taskApi.get(Number(route.params.id))
      form.name = task.name || ""
      form.sourceDsId = task.sourceDsId ?? null
      form.targetDsId = task.targetDsId ?? null
      form.sourceTable = task.sourceTable || ""
      form.targetTable = task.targetTable || ""
      form.syncMode = task.syncMode || "FULL_INCR"
      form.incrColumn = task.incrColumn || ""
      form.incrValue = task.incrValue || ""
      form.cronExpression = task.cronExpression || ""
      form.pageSize = task.pageSize ?? 1000
      form.batchSize = task.batchSize ?? 500
      savedTaskId.value = Number(route.params.id)
      mappingTaskId.value = savedTaskId.value
    } catch {
      // interceptor handles toast
    }
  }
})

// 选择数据源时加载表列表
watch(
  () => form.sourceDsId,
  async (newVal) => {
    if (newVal) {
      try {
        sourceTables.value = await datasourceApi.getTables(newVal)
      } catch {
        sourceTables.value = []
      }
    } else {
      sourceTables.value = []
    }
  },
)
watch(
  () => form.targetDsId,
  async (newVal) => {
    if (newVal) {
      try {
        targetTables.value = await datasourceApi.getTables(newVal)
      } catch {
        targetTables.value = []
      }
    } else {
      targetTables.value = []
    }
  },
)

async function handleSave() {
  saving.value = true
  try {
    const payload = { ...form, fieldMappings: fieldMappings.value }
    if (isEdit.value) {
      await taskApi.update(Number(route.params.id), payload)
      ElMessage.success("保存成功")
    } else {
      const created = await taskApi.create(payload)
      savedTaskId.value = created.id
      mappingTaskId.value = created.id
      ElMessage.success("创建成功，可继续配置字段映射")
    }
    router.push("/tasks")
  } catch {
    // interceptor handles toast
  } finally {
    saving.value = false
  }
}
</script>
