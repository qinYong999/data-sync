<template>
  <div class="login-page">
    <div class="login-card fade-in-up">
      <div class="login-brand">
        <div class="brand-icon">
          <svg viewBox="0 0 28 28" fill="none">
            <circle cx="14" cy="14" r="12" stroke="currentColor" stroke-width="1.5" opacity="0.25" />
            <path d="M6 14h16M14 6v16" stroke="currentColor" stroke-width="2" stroke-linecap="round" opacity="0.6" />
            <circle cx="6" cy="14" r="2.5" fill="currentColor" />
            <circle cx="14" cy="6" r="2.5" fill="currentColor" />
            <circle cx="22" cy="14" r="2.5" fill="currentColor" />
            <circle cx="14" cy="22" r="2.5" fill="currentColor" />
          </svg>
        </div>
        <div>
          <h1 class="brand-name">DataSync</h1>
          <div class="brand-sub">数据同步管理平台</div>
        </div>
      </div>

      <el-alert
        v-if="securityDisabled"
        type="info"
        :closable="false"
        show-icon
        title="后端未启用认证（app.security.enabled=false）"
        description="可直接进入系统；如需生产部署请启用表单登录与 CSRF。"
        class="login-alert"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="admin"
            autocomplete="username"
            size="large"
            :disabled="submitting"
            @keyup.enter="handleSubmit"
          >
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="登录密码"
            autocomplete="current-password"
            size="large"
            show-password
            :disabled="submitting"
            @keyup.enter="handleSubmit"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <div v-if="errorMessage" class="login-error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ errorMessage }}</span>
        </div>

        <el-button
          type="primary"
          size="large"
          class="login-submit"
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ submitting ? "登录中…" : "登 录" }}
        </el-button>
      </el-form>

      <div v-if="diagnostic" class="login-diagnostic">
        <el-icon><InfoFilled /></el-icon>
        <span>{{ diagnostic }}</span>
      </div>
    </div>

    <div class="login-footer">DataSync · 生产化改造版</div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from "vue"
import { useRoute, useRouter } from "vue-router"
import { ElMessage } from "element-plus"
import { User, Lock, InfoFilled, WarningFilled } from "@element-plus/icons-vue"
import { useAuth } from "@/composables/useAuth"
import { detectAuthMode } from "@/api/auth"

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const formRef = ref<{ validate: () => Promise<boolean> } | null>(null)
const submitting = ref(false)
const errorMessage = ref("")
const diagnostic = ref("")
const securityDisabled = ref(false)

const form = reactive({ username: "", password: "" })

const rules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
}

function redirectTarget(): string {
  const r = route.query.redirect
  if (typeof r === "string" && r && r !== "/login") return r
  return "/"
}

async function handleSubmit() {
  if (submitting.value) return
  errorMessage.value = ""

  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : true
  if (!valid) return

  submitting.value = true
  try {
    const res = await auth.login(form.username, form.password)
    if (res.success) {
      ElMessage.success(res.message || "登录成功")
      await router.replace(redirectTarget())
      return
    }
    errorMessage.value = res.message || "登录失败"
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : "登录失败，请稍后重试"
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // 探测后端认证能力：securityEnabled=false 时给出提示；端点不可达时给出联调提示
  try {
    const mode = await detectAuthMode()
    if (mode.securityEnabled === false) {
      securityDisabled.value = true
    } else if (mode.securityEnabled === null) {
      diagnostic.value =
        "未能连接到认证接口（/api/auth/session）。请确认后端已启动，或检查 Vite 代理配置。"
    }
  } catch {
    diagnostic.value = "认证接口探测失败，请检查后端是否已启动。"
  }
})
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  background:
    radial-gradient(circle at 18% 22%, rgba(13, 148, 136, 0.1), transparent 42%),
    radial-gradient(circle at 82% 78%, rgba(59, 130, 246, 0.09), transparent 45%),
    var(--bg-deepest);
}

.login-card {
  width: 100%;
  max-width: 400px;
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-elevated);
  padding: 34px 32px 28px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}
.brand-icon { width: 40px; height: 40px; color: var(--accent); flex-shrink: 0; }
.brand-icon svg { width: 100%; height: 100%; }
.brand-name { font-size: 22px; letter-spacing: -0.01em; }
.brand-sub { font-size: 12px; color: var(--text-muted); letter-spacing: 0.06em; }

.login-alert { margin-bottom: 18px; }

.login-error {
  display: flex; align-items: center; gap: 6px;
  margin: -4px 0 14px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: rgba(229, 62, 94, 0.08);
  color: #bc1c3e;
  font-size: 13px;
}

.login-submit { width: 100%; letter-spacing: 0.1em; }

.login-diagnostic {
  display: flex; align-items: flex-start; gap: 6px;
  margin-top: 16px; padding-top: 14px;
  border-top: 1px solid var(--border-subtle);
  font-size: 12px; color: var(--text-muted); line-height: 1.6;
}

.login-footer { font-size: 12px; color: var(--text-muted); letter-spacing: 0.06em; }
</style>
