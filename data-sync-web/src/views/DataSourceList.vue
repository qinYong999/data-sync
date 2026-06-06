<template>
  <div>
    <div class="page-header"><h2>?????</h2><el-button type="primary" @click="$router.push('/datasources/new')">?????</el-button></div>
    <div style="margin-bottom:12px">
      <el-input v-model="search" placeholder="???????..." prefix-icon="Search" clearable style="width:300px" />
    </div>
    <el-table :data="list" border stripe v-loading="loading" @sort-change="handleSort">
      <el-table-column prop="id" label="ID" width="70" sortable="custom" />
      <el-table-column prop="name" label="??" min-width="150" />
      <el-table-column prop="dbType" label="??" width="90"><template #default="{ row }"><el-tag :type="row.dbType==='MYSQL'?'success':'warning'">{{ row.dbType }}</el-tag></template></el-table-column>
      <el-table-column prop="host" label="??" width="150" />
      <el-table-column prop="port" label="??" width="70" />
      <el-table-column prop="databaseName" label="????" width="150" />
      <el-table-column label="??" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="testConn(row)">??</el-button>
          <el-button size="small" @click="$router.push('/datasources/'+row.id+'/edit')">??</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">??</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 0" background layout="prev, pager, next, sizes, total" :total="total" v-model:page="page" v-model:limit="size" style="margin-top:16px;justify-content:flex-end" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from "vue"
import { datasourceApi } from "@/api/datasource"
import { ElMessage, ElMessageBox } from "element-plus"
const list = ref<any[]>([]); const loading = ref(false); const total = ref(0)
const page = ref(1); const size = ref(10); const search = ref("")

const load = async () => {
  loading.value = true
  try {
    const res: any = await datasourceApi.list({ page: page.value - 1, size: size.value })
    list.value = res.content; total.value = res.totalElements
  } finally { loading.value = false }
}
const handleSort = (s: any) => { load() }
const testConn = async (row: any) => { try { const ok = await datasourceApi.test(row.id); ElMessage.success(ok ? "????" : "????") } catch (e: any) { ElMessage.error("??: " + e.message) } }
const handleDelete = async (row: any) => { await ElMessageBox.confirm("??????? \""+row.name+"\"?","??"); await datasourceApi.delete(row.id); ElMessage.success("???"); await load() }
watch([page, size, search], load, { deep: true })
onMounted(load)
</script>
<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
</style>
