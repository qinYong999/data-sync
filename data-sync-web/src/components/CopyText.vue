<template>
  <div class="copy-text" :class="{ 'is-block': block }">
    <pre v-if="block" class="copy-pre">{{ display }}</pre>
    <span v-else class="copy-inline">{{ display }}</span>
    <el-tooltip content="复制" placement="top" :show-after="300">
      <el-button
        v-if="showCopy"
        class="copy-btn"
        size="small"
        text
        @click.stop="doCopy"
      >
        <el-icon><DocumentCopy /></el-icon>
      </el-button>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue"
import { ElMessage } from "element-plus"
import { DocumentCopy } from "@element-plus/icons-vue"
import { copyText, toDisplayText } from "@/utils/format"

const props = withDefaults(
  defineProps<{
    /** 文本内容（可传对象，会自动 JSON 美化） */
    value?: unknown
    /** true = <pre> 块级展示（保留换行、可滚动） */
    block?: boolean
    /** 空值占位 */
    placeholder?: string
    /** 是否显示复制按钮 */
    showCopy?: boolean
  }>(),
  { block: false, placeholder: "—", showCopy: true },
)

const display = computed(() => {
  const t = toDisplayText(props.value)
  return t === "" ? props.placeholder : t
})

async function doCopy() {
  const ok = await copyText(toDisplayText(props.value))
  ok ? ElMessage.success("已复制") : ElMessage.error("复制失败，请手动选择文本")
}
</script>

<style scoped>
.copy-text { display: flex; align-items: flex-start; gap: 4px; min-width: 0; }
.copy-text.is-block { flex-direction: column; align-items: stretch; }
.copy-inline { white-space: pre-wrap; word-break: break-word; min-width: 0; }
.copy-pre {
  margin: 0; padding: 8px 10px; max-height: 280px; overflow: auto;
  background: var(--bg-secondary); border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono); font-size: 12px; line-height: 1.6;
  white-space: pre-wrap; word-break: break-all;
}
.copy-btn { flex-shrink: 0; opacity: 0.55; }
.copy-btn:hover { opacity: 1; }
</style>
