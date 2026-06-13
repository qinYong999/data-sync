<template>
  <div>
    <PageHeader>
      <template #title><h2>数据源管理</h2></template>
      <template #actions>
        <el-button type="primary" @click="$router.push('/datasources/new')">
          <el-icon><Plus /></el-icon>新增数据源
        </el-button>
      </template>
    </PageHeader>

    <div class="table-container fade-in-up delay-1">
      <el-table :data="data" v-loading="loading" empty-text="暂无数据，请先新增数据源" stripe border style="width:100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="类型" width="90">
          <template #default="{ row }"><StatusTag :value="row.dbType" /></template>
        </el-table-column>
        <el-table-column label="连接信息" min-width="160">
          <template #default="{ row }">
            <span class="conn-info">
              <span class="mono conn-host">{{ row.host }}:{{ row.port }}</span>
              <span class="conn-sep">/</span>
              <span>{{ row.databaseName }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" width="90" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="handleTest(row)" :plain="true">测试</el-button>
            <el-button size="small" @click="$router.push('/datasources/' + row.id + '/edit')">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)" :plain="true">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-pagination v-if="total > 0" background layout="prev,pager,next,sizes,total" :total="total" v-model:current-page="page" v-model:page-size="size" class="pagination" />
  </div>
</template>

<script setup lang="ts">
import { Plus } from "@element-plus/icons-vue"
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
  } catch {}
}

async function handleDelete(row: DataSourceVO) {
  if (!(await confirm(`确定删除数据源 "${row.name}"？删除后不可恢复。`, "删除确认"))) return
  try { await datasourceApi.delete(row.id); ElMessage.success("删除成功"); await load() } catch {}
}
</script>

<style scoped>
.conn-info { display: flex; align-items: center; gap: 5px; font-size: 13px; }
.conn-host { color: var(--accent); }
.conn-sep { color: var(--text-muted); opacity: 0.35; }
</style>
