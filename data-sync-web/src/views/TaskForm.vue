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
              <el-select v-model="form.incrColumn" filterable clearable placeholder="选择增量字段">
                <el-option v-for="c in sourceColumns" :key="c.name" :label="c.name + ' (' + c.type + ')'" :value="c.name" />
              </el-select>
              <div v-if="sourceColumns.length === 0" style="font-size:12px;color:var(--text-muted);margin-top:4px">
                请先选择源数据源和源表
              </div>
            </el-form-item>
            <el-form-item v-if="form.syncMode !== 'FULL' && form.incrColumn" label="增量起始值">
              <el-date-picker
                v-if="isIncrColumnTimeType"
                v-model="incrDateValue"
                type="datetime"
                placeholder="选择起始时间，留空自动获取"
                format="YYYY-MM-DD HH:mm:ss"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width:100%"
                clearable
              />
              <el-input v-else v-model="form.incrValue" placeholder="留空自动获取最大值" />
              <div style="font-size:12px;color:var(--text-muted);margin-top:4px">
                留空则首次执行时自动从源表获取最大值作为起始值
              </div>
            </el-form-item>
            <el-form-item label="Cron 表达式" class="cron-form-item">
              <div class="cron-input-row">
                <el-input v-model="form.cronExpression" placeholder="0 */5 * * * ?" class="cron-input" />
                <el-button size="small" @click="showCronBuilder = !showCronBuilder" :type="showCronBuilder ? 'primary' : 'default'" :plain="true">
                  {{ showCronBuilder ? "收起" : "可视化生成" }}
                </el-button>
              </div>

              <!-- 快捷预设 -->
              <div class="cron-presets" v-if="form.cronExpression">
                <span class="cron-preset-label">快捷:</span>
                <el-button size="small" @click="setCron('0 */5 * * * ?')" :plain="true">每5分钟</el-button>
                <el-button size="small" @click="setCron('0 */10 * * * ?')" :plain="true">每10分钟</el-button>
                <el-button size="small" @click="setCron('0 0 * * * ?')" :plain="true">每小时</el-button>
                <el-button size="small" @click="setCron('0 0 2 * * ?')" :plain="true">每天凌晨2点</el-button>
                <el-button size="small" @click="setCron('0 0 9 * * ?')" :plain="true">每天早9点</el-button>
                <el-button size="small" @click="setCron('')" :plain="true">清除</el-button>
              </div>

              <!-- 可视化生成器 -->
              <div v-if="showCronBuilder" class="cron-builder">
                <div class="cron-builder-row">
                  <span class="cron-builder-label">分钟</span>
                  <el-select v-model="cronMinute" size="small" style="width:100px">
                    <el-option v-for="m in 60" :key="m-1" :label="String(m-1).padStart(2,'0')" :value="m-1" />
                    <el-option label="每整点" :value="0" />
                    <el-option label="每5分钟" :value="null" />
                  </el-select>
                  <span class="cron-builder-hint">0-59, 选"每5分钟"则忽略下方小时设置</span>
                </div>
                <div class="cron-builder-row">
                  <span class="cron-builder-label">小时</span>
                  <el-select v-model="cronHour" size="small" style="width:100px" :disabled="cronMinute !== 0 && cronMinute !== null">
                    <el-option v-for="h in 24" :key="h-1" :label="String(h-1).padStart(2,'0')" :value="h-1" />
                    <el-option label="每小时" :value="null" />
                  </el-select>
                </div>
                <div class="cron-builder-row">
                  <span class="cron-builder-label">星期</span>
                  <el-checkbox-group v-model="cronWeekDays" size="small">
                    <el-checkbox-button v-for="d in weekDays" :key="d.value" :label="d.value">{{ d.label }}</el-checkbox-button>
                  </el-checkbox-group>
                </div>
                <div class="cron-builder-actions">
                  <el-button size="small" type="primary" @click="applyCronBuilder">应用</el-button>
                  <el-button size="small" @click="showCronBuilder = false">取消</el-button>
                </div>
              </div>
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
const sourceColumns = ref<any[]>([])
const datasources = ref<any[]>([])
const mappingTaskId = ref<number | null>(null)
const fieldMappings = ref<any[]>([])

// 判断增量字段是否为时间类型
const isIncrColumnTimeType = computed(() => {
  if (!form.incrColumn) return false
  const col = sourceColumns.value.find((c: any) => c.name === form.incrColumn)
  if (!col) return false
  const type = (col.type || "").toUpperCase()
  return type.includes("TIME") || type.includes("DATE") || type.includes("TIMESTAMP")
})

// 增量起始值日期绑定（el-date-picker <-> form.incrValue）
const incrDateValue = computed({
  get: () => form.incrValue || null,
  set: (val: any) => { form.incrValue = val || "" }
})

// Cron 表达式生成器
const showCronBuilder = ref(false)
const cronMinute = ref<number | null>(0)
const cronHour = ref<number | null>(null)
const cronWeekDays = ref<number[]>([])
const weekDays = [
  { label: "日", value: 1 },
  { label: "一", value: 2 },
  { label: "二", value: 3 },
  { label: "三", value: 4 },
  { label: "四", value: 5 },
  { label: "五", value: 6 },
  { label: "六", value: 7 },
]

function setCron(expr: string) {
  form.cronExpression = expr
}

function applyCronBuilder() {
  let expr = "0 "
  // 分钟
  if (cronMinute.value === null) {
    expr += "*/5 * * * ?"
  } else {
    expr += String(cronMinute.value) + " "
    // 小时
    if (cronHour.value === null) {
      expr += "* * * ?"
    } else {
      expr += String(cronHour.value) + " "
      // 日/Month固定为 * *
      expr += "* * "
      // 星期
      if (cronWeekDays.value.length > 0) {
        expr += cronWeekDays.value.sort((a, b) => a - b).join(",")
      } else {
        expr += "?"
      }
    }
  }
  form.cronExpression = expr
  showCronBuilder.value = false
}

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

// 源表变化时获取列信息（用于增量字段下拉选择）
watch([() => form.sourceDsId, () => form.sourceTable], async ([dsId, table]) => {
  if (dsId && table) {
    try {
      sourceColumns.value = await datasourceApi.getTableColumns(dsId, table)
    } catch { sourceColumns.value = [] }
  } else {
    sourceColumns.value = []
  }
}, { immediate: false })

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

/* Cron 生成器 */
.cron-form-item :deep(.el-form-item__content) { flex-direction: column; align-items: stretch; }
.cron-input-row { display: flex; gap: 8px; }
.cron-input { flex: 1; }
.cron-presets { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
.cron-preset-label { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
.cron-builder {
  margin-top: 10px; padding: 14px; border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md); background: var(--bg-secondary);
  display: flex; flex-direction: column; gap: 10px;
}
.cron-builder-row { display: flex; align-items: center; gap: 12px; }
.cron-builder-label { font-size: 13px; color: var(--text-secondary); min-width: 42px; }
.cron-builder-hint { font-size: 11px; color: var(--text-muted); }
.cron-builder-actions { display: flex; gap: 6px; margin-top: 4px; }

.mapping-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.mapping-placeholder { display: flex; flex-direction: column; align-items: center; padding: 40px 20px; color: var(--text-muted); text-align: center; gap: 10px; }
.mapping-placeholder p { margin: 0; font-size: 14px; }
.mapping-placeholder-icon { font-size: 32px; opacity: 0.2; }
</style>
