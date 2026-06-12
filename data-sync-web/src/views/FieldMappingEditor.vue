<template>
  <div class="mapping-editor">
    <el-button @click="fetchColumns" :loading="loading" size="small" :plain="true">获取表列信息</el-button>

    <div v-if="mappings.length" class="mapping-table">
      <div class="mapping-row header-row">
        <span class="col">源表列</span>
        <span class="arrow"></span>
        <span class="col">目标表列</span>
        <span class="col default-label">默认值</span>
        <span class="col action-label"></span>
      </div>
      <div v-for="(m, i) in mappings" :key="i" class="mapping-row data-row">
        <el-select v-model="m.sourceColumn" filterable placeholder="选择源列" size="small" class="col">
          <el-option v-for="c in sourceColumns" :key="c.name" :label="c.name" :value="c.name" />
        </el-select>
        <span class="arrow mono">→</span>
        <el-select v-model="m.targetColumn" filterable placeholder="选择目标列" size="small" class="col">
          <el-option v-for="c in targetColumns" :key="c.name" :label="c.name" :value="c.name" />
        </el-select>
        <el-input v-model="m.defaultValue" placeholder="可选" size="small" class="col" />
        <el-button size="small" type="danger" @click="removeMapping(i)" text>✕</el-button>
      </div>
    </div>

    <el-button v-if="mappings.length" size="small" @click="addMapping" :plain="true" class="add-btn">+ 添加映射</el-button>
    <div v-if="!mappings.length && !loading" class="empty-tip">点击上方按钮获取源表和目标表的列信息</div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue"
import { taskApi } from "@/api/task"
import type { ColumnInfo } from "@/types"

const props = defineProps<{ taskId?: number }>()
const emit = defineEmits<{ "update:mappings": [mappings: any[]] }>()

const loading = ref(false)
const sourceColumns = ref<ColumnInfo[]>([])
const targetColumns = ref<ColumnInfo[]>([])
const mappings = ref<any[]>([])

async function fetchColumns() {
  if (!props.taskId) return
  loading.value = true
  try {
    const data = await taskApi.getColumns(props.taskId)
    sourceColumns.value = data.sourceColumns || []
    targetColumns.value = data.targetColumns || []
    if (mappings.value.length === 0) {
      for (const sc of data.sourceColumns) {
        const tc = data.targetColumns.find((c: any) => c.name === sc.name)
        mappings.value.push({ sourceColumn: sc.name, targetColumn: tc ? tc.name : "", defaultValue: "", primaryKey: sc.primaryKey || false })
      }
    }
  } finally { loading.value = false }
}

function addMapping() { mappings.value.push({ sourceColumn: "", targetColumn: "", defaultValue: "", primaryKey: false }) }
function removeMapping(i: number) { mappings.value.splice(i, 1) }

watch(mappings, (v) => emit("update:mappings", v), { deep: true })
</script>

<style scoped>
.mapping-editor { padding: 4px 0; }
.mapping-table { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }
.mapping-row { display: flex; align-items: center; gap: 8px; }
.header-row { font-size: 12px; font-weight: 500; color: var(--text-muted); padding-bottom: 4px; border-bottom: 1px solid var(--border-subtle); margin-bottom: 4px; }
.col { flex: 1; min-width: 0; }
.arrow { flex-shrink: 0; width: 20px; text-align: center; color: var(--text-muted); opacity: 0.3; font-size: 13px; }
.data-row { background: rgba(255,255,255,0.015); padding: 4px 8px; border-radius: var(--radius-sm); }
.data-row:hover { background: var(--bg-card-hover); }
:deep(.el-select), :deep(.el-input) { width: 100%; }
.add-btn { margin-top: 12px; }
.empty-tip { padding: 24px 0; text-align: center; color: var(--text-muted); font-size: 13px; }
</style>
