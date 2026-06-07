<template>
  <div>
    <PageHeader>
      <template #title><h2>数据源管理</h2></template>
      <template #actions>
        <el-button type="primary" @click="$router.push('/datasources/new')">
          新增数据源
        </el-button>
      </template>
    </PageHeader>

    <el-table
      :data="data"
      border
      stripe
      v-loading="loading"
      empty-text="暂无数据，请先新增数据源"
      style="width: 100%"
    >
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <StatusTag :value="row.dbType" />
        </template>
      </el-table-column>
      <el-table-column prop="host" label="主机" width="150" />
      <el-table-column prop="port" label="端口" width="70" />
      <el-table-column prop="databaseName" label="数据库名" width="150" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="handleTest(row)">测试</el-button>
          <el-button size="small" @click="$router.push('/datasources/' + row.id + '/edit')">
            编辑
          </el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0"
      background
      layout="prev, pager, next, sizes, total"
      :total="total"
      v-model:current-page="page"
      v-model:page-size="size"
      style="margin-top: 16px; justify-content: flex-end"
    />
  </div>
</template>

<script setup lang="ts">
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"
import { usePagination } from "@/composables/usePagination"
import { useConfirm } from "@/composables/useConfirm"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import type { DataSourceVO } from "@/types"

const { data, loading, total, page, size, load } = usePagination<DataSourceVO>((p, s) =>
  datasourceApi.list({ page: p, size: s, sort: "id,desc" }),
)
load()

const confirm = useConfirm()

async function handleTest(row: DataSourceVO) {
  if (!row?.id) { ElMessage.warning("数据源ID不可用"); return }
  try {
    const ok = await datasourceApi.test(row.id)
    ElMessage.success(ok ? "连接成功" : "连接失败（请检查账号密码）")
  } catch {
    // interceptor handles toast
  }
}

async function handleDelete(row: DataSourceVO) {
  if (!(await confirm(`确定删除数据源 "${row.name}"？删除后不可恢复。`, "删除确认"))) return
  try {
    await datasourceApi.delete(row.id)
    ElMessage.success("删除成功")
    await load()
  } catch {
    // interceptor handles toast
  }
}
</script>
