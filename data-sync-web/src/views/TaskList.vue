<template>
  <div>
    <div class="page-header"><h2>同步任务</h2><el-button type="primary" @click="$router.push('/tasks/new')">新增任务</el-button></div>
    <el-table :data="tasks" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="任务名称" min-width="150" />
      <el-table-column label="源库" width="70"><template #default="{ row }">{{ row.sourceDsId }}</template></el-table-column>
      <el-table-column prop="sourceTable" label="源表" width="130" />
      <el-table-column label="目标库" width="70"><template #default="{ row }">{{ row.targetDsId }}</template></el-table-column>
      <el-table-column prop="targetTable" label="目标表" width="130" />
      <el-table-column prop="syncMode" label="模式" width="90"><template #default="{ row }"><el-tag>{{ row.syncMode }}</el-tag></template></el-table-column>
      <el-table-column prop="cronExpression" label="调度表达式" width="150" />
      <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag :type="row.status==='ENABLED'?'success':'info'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :type="row.status==='ENABLED'?'warning':'success'" @click="toggleStatus(row)">{{ row.status==='ENABLED'?'禁用':'启用' }}</el-button>
          <el-button size="small" @click="$router.push('/tasks/'+row.id+'/edit')">编辑</el-button>
          <el-button size="small" @click="$router.push('/tasks/'+row.id+'/records')">历史</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue"
import { taskApi } from "@/api/task"
import { ElMessage, ElMessageBox } from "element-plus"
const tasks = ref<any[]>([]); const loading = ref(false)
const load = async () => { loading.value = true; try { tasks.value = await taskApi.list() } finally { loading.value = false } }
const toggleStatus = async (row: any) => { if (row.status==="ENABLED") await taskApi.disable(row.id); else await taskApi.enable(row.id); ElMessage.success(row.status==="ENABLED"?"已禁用":"已启用"); await load() }
const handleDelete = async (row: any) => { await ElMessageBox.confirm("确定删除任务 \""+row.name+"\"？","提示"); await taskApi.delete(row.id); ElMessage.success("已删除"); await load() }
onMounted(load)
</script>
<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
</style>