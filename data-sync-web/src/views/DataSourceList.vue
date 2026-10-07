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
      <el-table
        :data="data"
        v-loading="loading"
        stripe
        border
        style="width:100%"
        empty-text="暂无数据源"
      >
        <template #empty>
          <EmptyState :visible="true" icon="◈" description="还没有配置任何数据源。新增一个 MySQL 数据源后即可创建同步任务。">
            <el-button type="primary" size="small" @click="$router.push('/datasources/new')">
              <el-icon><Plus /></el-icon>新增数据源
            </el-button>
          </EmptyState>
        </template>

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
        <el-table-column prop="username" label="用户名" width="110" />
        <el-table-column label="口令" width="90">
          <template #default>
            <span class="pwd-masked" title="出于安全考虑，接口不返回口令明文">
              <el-icon><Lock /></el-icon>已加密
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <div class="actions">
              <el-button size="small" @click="handleTest(row)" :loading="testingId === row.id" :plain="true">测试</el-button>
              <el-button size="small" @click="$router.push('/datasources/' + row.id + '/edit')">编辑</el-button>
              <el-button size="small" type="danger" @click="handleDelete(row)" :plain="true">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-pagination
      v-if="total > 0"
      background
      layout="prev,pager,next,sizes,total"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
    />

    <div v-if="total === 0 && !loading && !loadFailed" class="list-hint">
      提示：接口响应中的口令字段一律为空字符串（AES-GCM 密文只存在于数据库）。
    </div>
    <el-alert
      v-if="loadFailed"
      type="error"
      :closable="false"
      show-icon
      title="数据源列表加载失败"
      description="请检查后端服务是否已启动、登录会话是否有效，然后刷新页面重试。"
      class="fade-in-up"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue"
import { Plus, Lock } from "@element-plus/icons-vue"
import { datasourceApi } from "@/api/datasource"
import { toApiError } from "@/api/request"
import { ElMessage } from "element-plus"
import { usePagination } from "@/composables/usePagination"
import { useConfirm } from "@/composables/useConfirm"
import PageHeader from "@/components/PageHeader.vue"
import StatusTag from "@/components/StatusTag.vue"
import EmptyState from "@/components/EmptyState.vue"
import type { DataSourceVO } from "@/types"

const loadFailed = ref(false)
const testingId = ref<number | null>(null)

const { data, loading, total, page, size, load } = usePagination<DataSourceVO>((p, s) =>
  datasourceApi.list({ page: p, size: s, sort: "id,desc" }),
)

async function loadSafe() {
  const ok = await load()
  loadFailed.value = !ok
}
loadSafe()

const confirm = useConfirm()

async function handleTest(row: DataSourceVO) {
  if (!row?.id) { ElMessage.warning("数据源ID不可用"); return }
  testingId.value = row.id
  try {
    const res = await datasourceApi.testDetail(row.id)
    if (res.success) ElMessage.success(res.message || "连接成功")
    else ElMessage.error(res.message || "连接失败（请检查账号密码）")
  } catch (e) {
    ElMessage.error(toApiError(e).message)
  } finally {
    testingId.value = null
  }
}

async function handleDelete(row: DataSourceVO) {
  if (!(await confirm(`确定删除数据源 "${row.name}"？删除后不可恢复。`, "删除确认"))) return
  try {
    await datasourceApi.delete(row.id)
    ElMessage.success("删除成功")
    await loadSafe()
  } catch {
    /* 全局拦截器已提示 */
  }
}
</script>

<style scoped>
.conn-info { display: flex; align-items: center; gap: 5px; font-size: 13px; }
.conn-host { color: var(--accent); }
.conn-sep { color: var(--text-muted); opacity: 0.35; }
.pwd-masked { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-muted); }
.actions { display: flex; gap: 3px; flex-wrap: nowrap; }
.list-hint { margin-top: 12px; font-size: 12px; color: var(--text-muted); }
</style>
