<template>
  <div class="preflight-panel">
    <div v-if="loading" class="pf-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在预检（会连接源库与目标库，请稍候）…</span>
    </div>

    <template v-else-if="result">
      <div v-if="!hasIssues" class="pf-ok">
        <el-icon><CircleCheckFilled /></el-icon>
        <div>
          <div class="pf-ok-title">预检通过</div>
          <div class="pf-ok-desc">源表/目标表、字段映射、增量字段与目标主键均检查通过，可以执行同步。</div>
        </div>
      </div>

      <template v-else>
        <el-alert
          v-if="errorCount > 0"
          type="error"
          :closable="false"
          show-icon
          class="pf-summary"
          :title="`预检未通过：${errorCount} 项错误${warnCount > 0 ? '，' + warnCount + ' 项警告' : ''}`"
          description="存在 ERROR 时任务不允许执行（同步引擎也会在写入任何数据前拒绝）。请按下方建议逐项修复后重新预检。"
        />
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          class="pf-summary"
          :title="`预检通过，但有 ${warnCount} 项警告`"
          description="警告不影响执行，但建议按建议项优化（例如为增量字段加索引）。"
        />

        <div class="pf-list">
          <div
            v-for="(issue, i) in sortedIssues"
            :key="i"
            class="pf-item"
            :class="issue.level === 'ERROR' ? 'is-error' : 'is-warn'"
          >
            <div class="pf-item-head">
              <el-tag :type="issue.level === 'ERROR' ? 'danger' : 'warning'" size="small" effect="dark">
                {{ issue.level === "ERROR" ? "错误" : "警告" }}
              </el-tag>
              <span class="pf-code mono">{{ issue.code || "UNKNOWN" }}</span>
              <span class="pf-code-label">{{ codeLabel(issue.code) }}</span>
              <el-button
                class="pf-copy-one"
                size="small"
                text
                @click="copyOne(issue)"
              >复制</el-button>
            </div>
            <div v-if="issue.message" class="pf-message">{{ issue.message }}</div>
            <div class="pf-hint">
              <span class="pf-hint-label">建议</span>
              <span class="pf-hint-text">{{ suggestionFor(issue) }}</span>
            </div>
          </div>
        </div>

        <div class="pf-actions">
          <el-button size="small" @click="copyAll" :plain="true">
            <el-icon><DocumentCopy /></el-icon>复制全部问题
          </el-button>
        </div>
      </template>
    </template>

    <div v-else class="pf-empty">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ emptyText }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue"
import { ElMessage } from "element-plus"
import { Loading, CircleCheckFilled, InfoFilled, DocumentCopy } from "@element-plus/icons-vue"
import type { PreflightIssue, PreflightRes } from "@/types"
import { codeLabel, suggestionFor } from "@/utils/preflight"
import { copyText } from "@/utils/format"

const props = withDefaults(
  defineProps<{
    result: PreflightRes | null
    loading?: boolean
    emptyText?: string
  }>(),
  { loading: false, emptyText: '尚未预检，点击"预检"按钮开始检查。' },
)

const hasIssues = computed(() => (props.result?.issues?.length || 0) > 0)
const errorCount = computed(
  () => (props.result?.issues || []).filter((i) => i.level === "ERROR").length,
)
const warnCount = computed(
  () => (props.result?.issues || []).filter((i) => i.level !== "ERROR").length,
)
/** ERROR 排在 WARN 之前，便于运维先处理阻断项 */
const sortedIssues = computed<PreflightIssue[]>(() => {
  const list = props.result?.issues || []
  return [...list].sort((a, b) => {
    const av = a.level === "ERROR" ? 0 : 1
    const bv = b.level === "ERROR" ? 0 : 1
    return av - bv
  })
})

function issueToText(issue: PreflightIssue): string {
  return [
    `[${issue.level === "ERROR" ? "错误" : "警告"}] ${issue.code || "UNKNOWN"} ${codeLabel(issue.code)}`,
    issue.message ? `问题：${issue.message}` : "",
    `建议：${suggestionFor(issue)}`,
  ]
    .filter(Boolean)
    .join("\n")
}

async function copyOne(issue: PreflightIssue) {
  const ok = await copyText(issueToText(issue))
  ok ? ElMessage.success("已复制该问题") : ElMessage.error("复制失败，请手动选择文本")
}

async function copyAll() {
  const text = sortedIssues.value.map(issueToText).join("\n\n")
  const ok = await copyText(text || "预检通过，无问题")
  ok ? ElMessage.success("已复制全部预检结果") : ElMessage.error("复制失败，请手动选择文本")
}
</script>

<style scoped>
.preflight-panel { min-height: 60px; }

.pf-loading {
  display: flex; align-items: center; gap: 8px;
  padding: 22px 4px; color: var(--text-secondary); font-size: 13px;
}

.pf-ok {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 16px 16px;
  border: 1px solid rgba(16, 185, 129, 0.28);
  background: rgba(16, 185, 129, 0.07);
  border-radius: var(--radius-md);
  color: #047857;
}
.pf-ok .el-icon { font-size: 20px; margin-top: 1px; }
.pf-ok-title { font-weight: 600; font-size: 14px; }
.pf-ok-desc { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }

.pf-summary { margin-bottom: 12px; }

.pf-list { display: flex; flex-direction: column; gap: 10px; max-height: 46vh; overflow-y: auto; padding-right: 2px; }

.pf-item {
  border: 1px solid var(--border-subtle);
  border-left-width: 3px;
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: var(--bg-card);
}
.pf-item.is-error { border-left-color: var(--accent-rose); }
.pf-item.is-warn { border-left-color: var(--accent-amber); }

.pf-item-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pf-code { font-size: 12px; color: var(--text-secondary); background: var(--bg-secondary); padding: 1px 6px; border-radius: 3px; }
.pf-code-label { font-size: 12px; color: var(--text-muted); }
.pf-copy-one { margin-left: auto; }

.pf-message { margin-top: 6px; font-size: 13px; color: var(--text-primary); white-space: pre-wrap; word-break: break-word; }

.pf-hint { display: flex; gap: 6px; margin-top: 8px; font-size: 13px; line-height: 1.6; }
.pf-hint-label {
  flex-shrink: 0; font-size: 11px; font-weight: 600; color: var(--accent);
  background: var(--accent-dim); border-radius: 3px; padding: 1px 5px; height: fit-content; margin-top: 2px;
}
.pf-hint-text { color: var(--text-secondary); white-space: pre-wrap; word-break: break-word; }

.pf-actions { display: flex; justify-content: flex-end; margin-top: 12px; }

.pf-empty {
  display: flex; align-items: center; gap: 6px;
  padding: 20px 4px; color: var(--text-muted); font-size: 13px;
}
</style>
