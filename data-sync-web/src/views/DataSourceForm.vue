<template>
  <div>
    <h2>{{ isEdit ? "编辑数据源" : "新增数据源" }}</h2>
    <el-form :model="form" label-width="120px" style="max-width: 600px; margin-top: 20px">
      <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="数据库类型"><el-select v-model="form.dbType"><el-option label="MySQL" value="MYSQL" /><el-option label="达梦8" value="DM8" /></el-select></el-form-item>
      <el-form-item label="主机地址" required><el-input v-model="form.host" placeholder="127.0.0.1" /></el-form-item>
      <el-form-item label="端口" required><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item>
      <el-form-item label="数据库名" required><el-input v-model="form.databaseName" /></el-form-item>
      <el-form-item label="用户名" required><el-input v-model="form.username" /></el-form-item>
      <el-form-item label="密码" required><el-input v-model="form.password" type="password" show-password /></el-form-item>
      <el-form-item><el-button type="primary" @click="handleTest">测试连接</el-button><el-button type="success" @click="handleSave" :loading="saving">保存</el-button><el-button @click="$router.back()">取消</el-button></el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { useRoute, useRouter } from "vue-router"
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"
const route = useRoute(); const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const form = ref({ name: "", dbType: "MYSQL", host: "", port: 3306, databaseName: "", username: "", password: "" })
const saving = ref(false)
onMounted(async () => { if (isEdit.value) { const d = await datasourceApi.get(Number(route.params.id)); form.value = { name: d.name, dbType: d.dbType, host: d.host, port: d.port, databaseName: d.databaseName, username: d.username, password: "" } } })
const handleTest = async () => { try { const ok = await datasourceApi.test(Number(route.params.id)); ElMessage.success(ok ? "连接成功" : "连接失败") } catch { ElMessage.error("请先保存数据源") } }
const handleSave = async () => { saving.value = true; try { if (isEdit.value) await datasourceApi.update(Number(route.params.id), form.value); else await datasourceApi.create(form.value); ElMessage.success("保存成功"); router.push("/datasources") } finally { saving.value = false } }
</script>