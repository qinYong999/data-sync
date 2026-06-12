<template>
  <div>
    <PageHeader>
      <template #title><h2>{{ isEdit ? "编辑同步任务" : "新增同步任务" }}</h2></template>
    </PageHeader>

    <div class="form-layout">
      <div class="form-card fade-in-up delay-1">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <div class="form-grid">
            <el-form-item label="任务名称" prop="name">
              <el-input v-model="form.name" placeholder="my-sync-task" />
            </el-form-item>
            <el-form-item label="同步模式" prop="syncMode">
              <el-select v-model="form.syncMode">
                <el-option label="全量同步" value="FULL" />
                <el-option label="增量同步" value="INCR" />
                <el-option label="先全量后增量" value="FULL_INCR" />
              </el-select>
            </el-form-item>
          </div>

          <div class="form-section-label">数据源配置</div>
          <div class="form-grid">
            <el-form-item label="源数据源" prop="sourceDsId">
              <el-select v-model="form.sourceDsId" placeholder="选择源数据源">
                <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="源表名" prop="sourceTable">
              <el-select v-model="form.sourceTable" filterable clearable placeholder="先选择源数据源">
                <el-option v-for="t in sourceTables" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标数据源" prop="targetDsId">
              <el-select v-model="form.targetDsId" placeholder="选择目标数据源">
                <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标表名" prop="targetTable">
              <el-select v-model="form.targetTable" filterable clearable placeholder="先选择目标数据源">
                <el-option v-for="t in targetTables" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </div>

          <div class="form-section-label">高级配置</div>
          <div class="form-grid">
            <el-form-item v-if="form.syncMode !== 'FULL'" label="增量字段">
              <el-input v-model="form.incrColumn" placeholder="updated_at" />
            </el-form-item>
            <el-form-item label="Cron 表达式">
              <el-input v-model="form.cronExpression" placeholder="0 */5 * * * ?" />
            </el-form-item>
            <el-form-item label="每页行数">
              <el-input-number v-model="form.pageSize" :min="100" :max="10000" :step="100" />
            </el-form-item>
            <el-form-item label="批量写入行数">
              <el-input-number v-model="form.batchSize" :min="100" :max="5000" :step="100" />
            </el-form-item>
          </div>

          <div class="form-actions">
            <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
            <el-button @click="$router.back()">取消</el-button>
          </div>
        </el-form>
      </div>

      <div class="form-card fade-in-up delay-2">
        <div class="mapping-header">
          <h3>字段映射</h3>
          <el-button v-if="mappingTaskId" size="small" type="primary" @click="mappingKey++" :plain="true">获取列信息</el-button>
        </div>
        <FieldMappingEditor v-if="mappingTaskId" :task-id="mappingTaskId" :key="mappingKey" @update:mappings="onMappingsUpdate" />
        <div v-else class="mapping-placeholder">
          <span class="mapping-placeholder-icon">◈</span>
          <p>请先保存任务后再配置字段映射</p>
        </div>
      </div>
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
const mappingKey = ref(0)

const rules = {
  name: [{ required: true, message: "请输入任务名称", trigger: "blur" }],
  sourceDsId: [{ required: true, message: "请选择源数据源", trigger: "change" }],
  targetDsId: [{ required: true, message: "请选择目标数据源", trigger: "change" }],
  sourceTable: [{ required: true, message: "请选择源表", trigger: "change" }],
  targetTable: [{ required: true, message: "请选择目标表", trigger: "change" }],
  syncMode: [{ required: true, message: "请选择同步模式", trigger: "change" }],
}

const form = reactive<TaskFormType>({
  name: "", sourceDsId: null, targetDsId: null, sourceTable: "", targetTable: "",
  syncMode: "FULL_INCR", incrColumn: "", incrValue: "", cronExpression: "", pageSize: 1000, batchSize: 500,
})

const sourceTables = ref<string[]>([])
const targetTables = ref<string[]>([])
const datasources = ref<any[]>([])
const mappingTaskId = ref<number | null>(null)
const fieldMappings = ref<any[]>([])

function onMappingsUpdate(mappings: any[]) { fieldMappings.value = mappings }

onMounted(async () => {
  try {
    const res: any = await datasourceApi.list({ page: 0, size: 999, sort: "id,desc" })
    datasources.value = res.content || []
  } catch {}

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
      mappingTaskId.value = Number(route.params.id)
    } catch {}
  }
})

watch(() => form.sourceDsId, async (v) => { try { sourceTables.value = v ? await datasourceApi.getTables(v) : [] } catch { sourceTables.value = [] } })
watch(() => form.targetDsId, async (v) => { try { targetTables.value = v ? await datasourceApi.getTables(v) : [] } catch { targetTables.value = [] } })

async function handleSave() {
  saving.value = true
  try {
    const payload = { ...form, fieldMappings: fieldMappings.value }
    if (isEdit.value) { await taskApi.update(Number(route.params.id), payload); ElMessage.success("保存成功") }
    else { const c = await taskApi.create(payload); mappingTaskId.value = c.id; ElMessage.success("创建成功，可继续配置字段映射") }
    router.push("/tasks")
  } catch {} finally { saving.value = false }
}
</script>

<style scoped>
.form-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: start; }
@media (max-width: 1000px) { .form-layout { grid-template-columns: 1fr; } }

.form-card {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 24px;
}
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 2px 20px; }
.form-section-label {
  font-size: 12px; font-weight: 600; color: var(--text-muted);
  text-transform: uppercase; letter-spacing: 0.05em;
  margin-top: 20px; margin-bottom: 8px; padding-top: 16px; border-top: 1px solid var(--border-subtle);
}
.form-actions { display: flex; gap: 10px; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--border-subtle); }
:deep(.el-input-number), :deep(.el-select) { width: 100%; }

.mapping-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.mapping-placeholder { display: flex; flex-direction: column; align-items: center; padding: 40px 20px; color: var(--text-muted); text-align: center; gap: 10px; }
.mapping-placeholder p { margin: 0; font-size: 14px; }
.mapping-placeholder-icon { font-size: 32px; opacity: 0.2; }
</style>
