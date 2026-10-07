<template>
  <el-dialog
    :model-value="modelValue"
    :title="taskName ? `任务预检 — ${taskName}` : '任务预检'"
    width="760px"
    top="6vh"
    append-to-body
    destroy-on-close
    @update:model-value="onVisibleChange"
    @open="run"
  >
    <PreflightPanel :result="result" :loading="loading" />
    <template #footer>
      <el-button @click="onVisibleChange(false)">关闭</el-button>
      <el-button type="primary" :loading="loading" @click="run">
        <el-icon><Refresh /></el-icon>重新预检
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from "vue"
import { ElMessage } from "element-plus"
import { Refresh } from "@element-plus/icons-vue"
import { taskApi } from "@/api/task"
import PreflightPanel from "@/components/PreflightPanel.vue"
import { preflightHasError } from "@/utils/preflight"
import type { PreflightRes } from "@/types"

const props = defineProps<{
  modelValue: boolean
  taskId: number | null
  taskName?: string
}>()

const emit = defineEmits<{
  "update:modelValue": [v: boolean]
  /** 预检结果回传父组件，便于列表缓存后决定"执行"按钮是否可用 */
  result: [taskId: number, res: PreflightRes]
}>()

const loading = ref(false)
const result = ref<PreflightRes | null>(null)

function onVisibleChange(v: boolean) {
  emit("update:modelValue", v)
}

async function run() {
  if (!props.taskId) return
  loading.value = true
  try {
    const res = await taskApi.preflight(props.taskId, false)
    result.value = res
    emit("result", props.taskId, res)
    if (preflightHasError(res)) {
      ElMessage.warning("预检未通过：存在 " + res.issues.filter((i) => i.level === "ERROR").length + " 项错误")
    } else if (res.issues.length > 0) {
      ElMessage.warning("预检通过，但有 " + res.issues.length + " 项警告")
    } else {
      ElMessage.success("预检通过")
    }
  } catch {
    result.value = null
  } finally {
    loading.value = false
  }
}

/** 打开时清空上次结果，避免看到上一个任务的问题 */
watch(
  () => props.taskId,
  () => {
    result.value = null
  },
)

defineExpose({ run })
</script>
