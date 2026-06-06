<template>
  <div>
    <h2>{{ isEdit ? "编辑同步任务" : "新增同步任务" }}</h2>
    <el-form :model="form" label-width="140px" style="max-width: 700px; margin-top: 20px">
      <el-form-item label="任务名称" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="源数据源" required>
        <el-select v-model="form.sourceDsId" style="width:100%"><el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" /></el-select>
      </el-form-item>
      <el-form-item label="源表名" required><el-input v-model="form.sourceTable" /></el-form-item>
      <el-form-item label="目标数据源" required>
        <el-select v-model="form.targetDsId" style="width:100%"><el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" /></el-select>
      </el-form-item>
      <el-form-item label="目标表名" required><el-input v-model="form.targetTable" /></el-form-item>
      <el-form-item label="同步模式" required>
        <el-select v-model="form.syncMode"><el-option label="全量同步" value="FULL" /><el-option label="增量同步" value="INCR" /><el-option label="先全量后增量" value="FULL_INCR" /></el-select>
      </el-form-item>
      <el-form-item label="增量字段" v-if="form.syncMode!=='FULL'"><el-input v-model="form.incrColumn" placeholder="updated_at" /></el-form-item>
      <el-form-item label="Cron 表达式"><el-input v-model="form.cronExpression" placeholder="0 */5 * * * ?" /></el-form-item>
      <el-form-item label="每页行数"><el-input-number v-model="form.pageSize" :min="100" :max="10000" :step="100" /></el-form-item>
      <el-form-item label="批量写入"><el-input-number v-model="form.batchSize" :min="100" :max="5000" :step="100" /></el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
        <el-button @click="$router.back()">取消</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { useRoute, useRouter } from "vue-router"
import { taskApi } from "@/api/task"
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"
const route = useRoute(); const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const form = ref({ name: "", sourceDsId: null, targetDsId: null, sourceTable: "", targetTable: "", syncMode: "FULL_INCR", incrColumn: "", incrValue: "", cronExpression: "", pageSize: 1000, batchSize: 500 })
const datasources = ref<any[]>([]); const saving = ref(false)
onMounted(async () => { datasources.value = await datasourceApi.list(); if (isEdit.value) { const t = await taskApi.get(Number(route.params.id)); form.value = { ...t, sourceDsId: t.sourceDsId, targetDsId: t.targetDsId } } })
const handleSave = async () => { saving.value = true; try { if (isEdit.value) await taskApi.update(Number(route.params.id), form.value); else await taskApi.create(form.value); ElMessage.success("保存成功"); router.push("/tasks") } finally { saving.value = false } }
</script>