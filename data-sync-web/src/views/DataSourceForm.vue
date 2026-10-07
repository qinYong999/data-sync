<template>
  <div>
    <PageHeader>
      <template #title><h2>{{ isEdit ? "编辑数据源" : "新增数据源" }}</h2></template>
    </PageHeader>

    <div class="form-card fade-in-up delay-1" v-loading="loading">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" placeholder="my-database" />
          </el-form-item>
          <el-form-item label="数据库类型" prop="dbType">
            <el-select v-model="form.dbType">
              <el-option label="MySQL" value="MYSQL" />
              <el-option label="达梦 DM8（未在真实实例验证）" value="DM8" />
            </el-select>
          </el-form-item>
          <el-form-item label="主机地址" prop="host">
            <el-input v-model="form.host" placeholder="127.0.0.1" />
          </el-form-item>
          <el-form-item label="端口" prop="port">
            <el-input-number v-model="form.port" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="数据库名" prop="databaseName">
            <el-input v-model="form.databaseName" placeholder="datasync" />
          </el-form-item>
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="root" />
          </el-form-item>
          <el-form-item :label="isEdit ? '密码（留空表示不修改）' : '密码'" prop="password" class="form-full">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="isEdit ? '留空则保留原口令不变' : '连接密码'"
            />
            <div class="field-hint">
              <template v-if="isEdit">
                出于安全考虑，后端不会下发已存口令（接口返回空字符串）。
                <span class="hint-strong">留空即保持原口令不变</span>；如需更换请直接输入新口令。
              </template>
              <template v-else>
                口令将以 AES-GCM 加密后落库，接口响应与日志均不会出现明文。
              </template>
            </div>
          </el-form-item>
        </div>

        <div v-if="testMessage" class="test-result" :class="testOk ? 'ok' : 'fail'">
          <el-icon><component :is="testOk ? CircleCheckFilled : CircleCloseFilled" /></el-icon>
          <span>{{ testMessage }}</span>
        </div>

        <div class="form-actions">
          <el-button type="primary" :loading="testing" @click="handleTest" :plain="true">测试连接</el-button>
          <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
          <el-button @click="$router.back()">取消</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ElMessage } from "element-plus"
import { CircleCheckFilled, CircleCloseFilled } from "@element-plus/icons-vue"
import { datasourceApi, buildDataSourcePayload } from "@/api/datasource"
import PageHeader from "@/components/PageHeader.vue"
import type { DataSourceForm as DataSourceFormType } from "@/types"

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const formRef = ref<{ validate: () => Promise<boolean> } | null>(null)
const savedId = ref<number | null>(null)
const saving = ref(false)
const testing = ref(false)
const loading = ref(false)
const testMessage = ref("")
const testOk = ref(false)

const rules = computed(() => ({
  name: [{ required: true, message: "请输入数据源名称", trigger: "blur" }],
  host: [{ required: true, message: "请输入主机地址", trigger: "blur" }],
  port: [{ required: true, message: "请输入端口", trigger: "blur" }],
  databaseName: [{ required: true, message: "请输入数据库名", trigger: "blur" }],
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  // 编辑态允许留空（表示不修改），新增态必填
  password: isEdit.value
    ? []
    : [{ required: true, message: "请输入密码", trigger: "blur" }],
  dbType: [{ required: true, message: "请选择数据库类型", trigger: "change" }],
}))

const form = reactive<DataSourceFormType>({
  name: "", dbType: "MYSQL", host: "127.0.0.1", port: 3306, databaseName: "", username: "root", password: "",
})

onMounted(async () => {
  if (!isEdit.value) return
  loading.value = true
  try {
    const d = await datasourceApi.get(Number(route.params.id))
    form.name = d.name
    form.dbType = d.dbType
    form.host = d.host
    form.port = d.port
    form.databaseName = d.databaseName
    form.username = d.username
    // 安全要求：绝不回填口令。即使后端因故下发了 password（明文或掩码），也一律丢弃。
    form.password = ""
    savedId.value = d.id
  } catch {
    // 全局拦截器已提示
  } finally {
    loading.value = false
  }
})

async function handleTest() {
  testMessage.value = ""
  testing.value = true
  try {
    // 编辑态且未输入新口令 → 用后端已存口令测试；否则用表单值直接测试
    const useFormValue = !isEdit.value || !!form.password
    const res = useFormValue
      ? await datasourceApi.testDetailDirect(buildDataSourcePayload(form, false))
      : await datasourceApi.testDetail(savedId.value!)
    testOk.value = !!res.success
    testMessage.value = res.message || (res.success ? "连接成功" : "连接失败")
    if (res.success) ElMessage.success("连接成功")
  } catch (e) {
    testOk.value = false
    testMessage.value = e instanceof Error ? e.message : "连接测试失败"
  } finally {
    testing.value = false
  }
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) { ElMessage.warning("请填写必填项"); return }

  saving.value = true
  try {
    if (isEdit.value) {
      // 口令留空 → 不下发 password 字段，后端保留原口令
      await datasourceApi.update(Number(route.params.id), buildDataSourcePayload(form, true))
    } else {
      const res = await datasourceApi.create(buildDataSourcePayload(form, false))
      savedId.value = res.id
    }
    ElMessage.success("保存成功")
    router.push("/datasources")
  } catch {
    // 全局拦截器已提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-card {
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 24px;
  max-width: 680px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 20px;
}
.form-full { grid-column: 1 / -1; }
.field-hint {
  font-size: 12px; color: var(--text-muted); line-height: 1.6; margin-top: 4px;
}
.hint-strong { color: var(--accent); font-weight: 500; }

.test-result {
  display: flex; align-items: flex-start; gap: 6px;
  margin-top: 16px; padding: 10px 12px;
  border-radius: var(--radius-sm); font-size: 13px; line-height: 1.6;
  word-break: break-word;
}
.test-result.ok { background: rgba(16, 185, 129, 0.08); color: #047857; }
.test-result.fail { background: rgba(229, 62, 94, 0.08); color: #bc1c3e; }

.form-actions {
  display: flex; gap: 10px;
  margin-top: 24px; padding-top: 20px;
  border-top: 1px solid var(--border-subtle);
}
:deep(.el-input-number), :deep(.el-select) { width: 100%; }
</style>
