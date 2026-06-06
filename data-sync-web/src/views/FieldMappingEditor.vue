<template>
  <div class="field-mapping-editor">
    <el-alert title="?????????????????" type="info" :closable="false" show-icon style="margin-bottom:16px" />
    <el-button @click="fetchColumns" :loading="loading" type="primary" size="small">
      ??????
    </el-button>
    <el-table :data="mappings" border stripe style="margin-top:12px" v-if="mappings.length">
      <el-table-column label="???" width="180">
        <template #default="{ row, $index }">
          <el-select v-model="row.sourceColumn" filterable placeholder="?????" size="small">
            <el-option v-for="c in sourceColumns" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="????" width="180">
        <template #default="{ row, $index }">
          <el-select v-model="row.targetColumn" filterable placeholder="??????" size="small">
            <el-option v-for="c in targetColumns" :key="c.name" :label="c.name" :value="c.name" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="???" width="150">
        <template #default="{ row }">
          <el-input v-model="row.defaultValue" placeholder="??" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="??" width="80">
        <template #default="{ $index }">
          <el-button size="small" type="danger" @click="removeMapping($index)">??</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button size="small" @click="addMapping" style="margin-top:8px">+ ????</el-button>
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
      // Auto-match columns with same name
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
