<template>
  <div>
    <PageHeader>
      <template #title><h2>{{ isEdit ? "编辑数据源" : "新增数据源" }}</h2></template>
    </PageHeader>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="120px"
      style="max-width: 600px"
    >
      <el-form-item label="名称" prop="name" required>
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="数据库类型" prop="dbType" required>
        <el-select v-model="form.dbType">
          <el-option label="MySQL" value="MYSQL" />
          <el-option label="达梦8" value="DM8" />
        </el-select>
      </el-form-item>
      <el-form-item label="主机地址" prop="host" required>
        <el-input v-model="form.host" placeholder="127.0.0.1" />
      </el-form-item>
      <el-form-item label="端口" prop="port" required>
        <el-input-number v-model="form.port" :min="1" :max="65535" />
      </el-form-item>
      <el-form-item label="数据库名" prop="databaseName" required>
        <el-input v-model="form.databaseName" />
      </el-form-item>
      <el-form-item label="用户名" prop="username" required>
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="密码" prop="password" required>
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleTest">测试连接</el-button>
        <el-button type="success" @click="handleSave" :loading="saving">保存</el-button>
        <el-button @click="$router.back()">取消</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from "vue"
import { useRoute, useRouter } from "vue-router"
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"
import PageHeader from "@/components/PageHeader.vue"
import type { DataSourceForm as DataSourceFormType } from "@/types"

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const formRef = ref<any>(null)
const savedId = ref<number | null>(null)
const saving = ref(false)

const rules = {
  name: [{ required: true, message: "请输入数据源名称", trigger: "blur" }],
  host: [{ required: true, message: "请输入主机地址", trigger: "blur" }],
  port: [{ required: true, message: "请输入端口", trigger: "blur" }],
  databaseName: [{ required: true, message: "请输入数据库名", trigger: "blur" }],
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
  dbType: [{ required: true, message: "请选择数据库类型", trigger: "change" }],
}

const form = reactive<DataSourceFormType>({
  name: "",
  dbType: "MYSQL",
  host: "127.0.0.1",
  port: 3306,
  databaseName: "",
  username: "root",
  password: "",
})

onMounted(async () => {
  if (isEdit.value) {
    try {
      const d = await datasourceApi.get(Number(route.params.id))
      form.name = d.name
      form.dbType = d.dbType
      form.host = d.host
      form.port = d.port
      form.databaseName = d.databaseName
      form.username = d.username
      form.password = ""
      savedId.value = d.id
    } catch {
      // interceptor handles toast
    }
  }
})

async function handleTest() {
  try {
    const ok = isEdit.value
      ? await datasourceApi.test(savedId.value!)
      : await datasourceApi.testDirect(form)
    ElMessage.success(ok ? "连接成功" : "连接失败（请检查配置）")
  } catch {
    // interceptor handles toast
  }
}

async function handleSave() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    ElMessage.warning("请填写必填项")
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await datasourceApi.update(Number(route.params.id), form)
    } else {
      const res = await datasourceApi.create(form)
      savedId.value = res.id
    }
    ElMessage.success("保存成功")
    router.push("/datasources")
  } finally {
    saving.value = false
  }
}
</script>
