<template>
  <div>
    <div class="page-header"><h2>数据源管理</h2><el-button type="primary" @click="$router.push('/datasources/new')">新增数据源</el-button></div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column prop="dbType" label="类型" width="90"><template #default="{ row }"><el-tag :type="row.dbType==='MYSQL'?'success':'warning'">{{ row.dbType }}</el-tag></template></el-table-column>
      <el-table-column prop="host" label="主机" width="150" />
      <el-table-column prop="port" label="端口" width="70" />
      <el-table-column prop="databaseName" label="数据库名" width="150" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="testConn(row)">测试</el-button>
          <el-button size="small" @click="$router.push('/datasources/'+row.id+'/edit')">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue"
import { datasourceApi } from "@/api/datasource"
import { ElMessage, ElMessageBox } from "element-plus"
const list = ref<any[]>([]); const loading = ref(false)
const load = async () => { loading.value = true; try { list.value = await datasourceApi.list() } finally { loading.value = false } }
const testConn = async (row: any) => { try { const ok = await datasourceApi.test(row.id); ElMessage.success(ok ? "连接成功" : "连接失败") } catch (e: any) { ElMessage.error("异常: " + e.message) } }
const handleDelete = async (row: any) => { await ElMessageBox.confirm("确定删除数据源 \"" + row.name + "\"？", "提示"); await datasourceApi.delete(row.id); ElMessage.success("已删除"); await load() }
onMounted(load)
</script>
<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
</style>