<template>
  <div class="field-mapping-editor">
    <el-alert title="配置源表与目标表之间的字段映射关系" type="info" :closable="false" show-icon style="margin-bottom:16px" />
    <el-button @click="fetchColumns" :loading="loading" type="primary" size="small">
      获取表列信息
    </el-button>
    <el-table :data="mappings" border stripe style="margin-top:12px" v-if="mappings.length">
      <el-table-column label="源表列" width="180">
        <template #default="{ row, $index }">
          <el-select v-model="row.sourceColumn" filterable placeholder="请选择源列" size="small">
            <el-option v-for="c in sourceColumns" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="目标表列" width="180">
        <template #default="{ row, $index }">
          <el-select v-model="row.targetColumn" filterable placeholder="请选择目标列" size="small">
            <el-option v-for="c in targetColumns" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="默认值" width="150">
        <template #default="{ row }">
          <el-input v-model="row.defaultValue" placeholder="可选" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ $index }">
          <el-button size="small" type="danger" @click="removeMapping($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button size="small" @click="addMapping" style="margin-top:8px">+ 添加映射</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue"
import { taskApi } from "@/api/task"

const props = defineProps<{ taskId?: number }>()
const emit = defineEmits(["update:mappings"])

const loading = ref(false)
const sourceColumns = ref<any[]>([])
const targetColumns = ref<any[]>([])
const mappings = ref<any[]>([])

const fetchColumns = async () => {
  if (!props.taskId) return
  loading.value = true
  try {
    const data = await taskApi.getColumns(props.taskId)
    sourceColumns.value = data.sourceColumns
    targetColumns.value = data.targetColumns
    if (mappings.value.length === 0) {
      for (const sc of data.sourceColumns) {
        const tc = data.targetColumns.find((c: any) => c.name === sc.name)
        mappings.value.push({ sourceColumn: sc.name, targetColumn: tc ? tc.name : "", defaultValue: "", primaryKey: sc.primaryKey || false })
      }
    }
  } finally { loading.value = false }
}

const addMapping = () => mappings.value.push({ sourceColumn: "", targetColumn: "", defaultValue: "", primaryKey: false })
const removeMapping = (index: number) => mappings.value.splice(index, 1)

watch(mappings, (v) => emit("update:mappings", v), { deep: true })
</script>