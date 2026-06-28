<template>
  <el-dialog v-model="dialogVisible" title="SQL 编辑器" width="900px" top="5vh" :close-on-click-modal="false" @closed="handleClosed">
    <div class="sql-dialog-body">
      <!-- 工具栏 -->
      <div class="sql-toolbar">
        <el-button size="small" @click="formatSql" :plain="true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="3" y1="3" x2="9" y2="9"/></svg>
          美化 SQL
        </el-button>
        <el-button size="small" @click="runPreview" :loading="previewLoading" :plain="true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><polygon points="5,3 19,12 5,21"/></svg>
          预览结果
        </el-button>
        <el-button size="small" @click="fetchColumns" :loading="columnsLoading" :plain="true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><polyline points="22,12 18,12 15,21 9,3 6,12 2,12"/></svg>
          获取列信息
        </el-button>
        <span style="flex:1"></span>
        <span v-if="errorHint" class="sql-error-hint">{{ errorHint }}</span>
      </div>

      <!-- SQL 编辑器 -->
      <div class="sql-editor-wrap" :class="{ 'has-error': errorHint }">
        <div class="sql-line-numbers">
          <div v-for="n in lineCount" :key="n" class="sql-line-num">{{ n }}</div>
        </div>
        <textarea
          ref="textareaRef"
          v-model="localSql"
          class="sql-textarea"
          spellcheck="false"
          placeholder="SELECT ... FROM ... WHERE ..."
          @input="validateSql"
          @keydown.tab.prevent="insertTab"
          @scroll="syncScroll"
        ></textarea>
      </div>

      <!-- 错误/警告提示 -->
      <div v-if="errorHint" class="sql-error-bar">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
        {{ errorHint }}
      </div>

      <!-- 预览结果 -->
      <div v-if="previewData.length > 0" class="sql-preview-area">
        <div class="sql-preview-header">预览结果（前 {{ previewData.length }} 行）</div>
        <el-table :data="previewData" border size="small" max-height="240" style="width:100%">
          <el-table-column v-for="col in previewColumns" :key="col" :prop="col" :label="col" show-overflow-tooltip />
        </el-table>
      </div>
      <div v-else-if="previewDone && previewData.length === 0 && !previewLoading" class="sql-preview-empty">
        SQL 执行成功，无返回数据
      </div>

      <!-- 列信息 -->
      <div v-if="columns.length > 0" class="sql-columns-bar">
        <span class="sql-columns-label">检测到 {{ columns.length }} 个列：</span>
        <span v-for="c in columns" :key="c.name" class="sql-column-tag">
          <span class="sql-col-name">{{ c.name }}</span>
          <span class="sql-col-type">{{ c.type }}</span>
        </span>
      </div>
    </div>

    <template #footer>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch } from "vue"
import { datasourceApi } from "@/api/datasource"
import { ElMessage } from "element-plus"

const props = defineProps<{
  visible: boolean
  sql: string
  datasourceId: number | null
}>()

const emit = defineEmits<{
  "update:sql": [value: string]
  close: []
}>()

const dialogVisible = ref(props.visible)
const localSql = ref(props.sql || "")
const textareaRef = ref<HTMLTextAreaElement | null>(null)

const previewLoading = ref(false)
const columnsLoading = ref(false)
const previewData = ref<any[]>([])
const previewColumns = ref<string[]>([])
const previewDone = ref(false)
const columns = ref<any[]>([])
const errorHint = ref("")

const lineCount = computed(() => {
  return (localSql.value || "").split("\n").length
})

watch(() => props.visible, (v) => { dialogVisible.value = v })
watch(() => dialogVisible.value, (v) => { if (!v) emit("close") })

// 行号滚动同步
function syncScroll() {
  const ta = textareaRef.value
  if (!ta) return
  const nums = ta.parentElement?.querySelector(".sql-line-numbers") as HTMLElement
  if (nums) nums.scrollTop = ta.scrollTop
}

// Tab 键插入空格
function insertTab(e: KeyboardEvent) {
  const ta = e.target as HTMLTextAreaElement
  const start = ta.selectionStart
  const end = ta.selectionEnd
  localSql.value = localSql.value.substring(0, start) + "  " + localSql.value.substring(end)
  setTimeout(() => ta.setSelectionRange(start + 2, start + 2))
}

// SQL 美化（简单格式化）
function formatSql() {
  const sql = localSql.value.trim()
  if (!sql) return
  let formatted = sql
    // 关键字大写并在前面换行
    .replace(/\b(SELECT|FROM|WHERE|AND|OR|ORDER\s+BY|GROUP\s+BY|HAVING|LIMIT|OFFSET|JOIN|LEFT\s+JOIN|RIGHT\s+JOIN|INNER\s+JOIN|ON|UNION|AS|IN|NOT|NULL|IS|BETWEEN|LIKE|INTO|VALUES|SET|UPDATE|DELETE|INSERT)\b/gi,
      (match) => {
        const upper = match.toUpperCase()
        if (["SELECT", "FROM", "WHERE", "ORDER BY", "GROUP BY", "HAVING", "LIMIT",
             "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "ON", "UNION",
             "AND", "OR", "VALUES", "SET"].includes(upper)) {
          return "\n" + upper
        }
        return upper
      })
    // 清理多余空行
    .replace(/\n\s*\n/g, "\n")
    .trim()
  localSql.value = formatted
  validateSql()
}

// 基本 SQL 语法检测
function validateSql() {
  const sql = localSql.value.trim()
  if (!sql) { errorHint.value = ""; return }

  const upper = sql.toUpperCase()

  // 禁止关键字检查
  const blocked = ["INSERT ", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE ", "ALTER ", "CREATE "]
  for (const kw of blocked) {
    if (upper.includes(kw) && !upper.startsWith("SELECT")) {
      errorHint.value = "SQL 中包含不允许的语句: " + kw.trim()
      return
    }
  }

  // 基本结构检查
  if (!upper.startsWith("SELECT")) {
    errorHint.value = "SQL 必须以 SELECT 开头"
    return
  }
  if (!upper.includes(" FROM ") && !upper.includes("\nFROM ")) {
    errorHint.value = "SQL 缺少 FROM 子句"
    return
  }

  // 括号匹配检查
  const opens = (sql.match(/\(/g) || []).length
  const closes = (sql.match(/\)/g) || []).length
  if (opens !== closes) {
    errorHint.value = `括号不匹配：左括号 ${opens} 个，右括号 ${closes} 个`
    return
  }

  // 分号位置检查
  const semiIdx = sql.indexOf(";")
  if (semiIdx >= 0 && semiIdx < sql.trim().length - 1) {
    errorHint.value = "分号后还存在其他内容"
    return
  }

  errorHint.value = ""
}

async function runPreview() {
  if (!props.datasourceId || !localSql.value.trim()) {
    ElMessage.warning("请先选择源数据源并填写 SQL")
    return
  }
  previewLoading.value = true
  previewData.value = []
  previewColumns.value = []
  previewDone.value = false
  try {
    const data = await datasourceApi.previewSql(props.datasourceId, localSql.value)
    if (data.length > 0) {
      previewColumns.value = Object.keys(data[0])
      previewData.value = data
    } else {
      ElMessage.info("SQL 执行成功，无返回数据")
    }
  } catch {
    previewData.value = []
    previewColumns.value = []
  } finally {
    previewDone.value = true
    previewLoading.value = false
  }
}

async function fetchColumns() {
  if (!props.datasourceId || !localSql.value.trim()) {
    ElMessage.warning("请先选择源数据源并填写 SQL")
    return
  }
  columnsLoading.value = true
  try {
    const cols = await datasourceApi.getSqlColumns(props.datasourceId, localSql.value)
    columns.value = cols
    ElMessage.success("获取到 " + cols.length + " 个列")
  } catch {
    columns.value = []
  } finally {
    columnsLoading.value = false
  }
}

function confirm() {
  emit("update:sql", localSql.value)
  dialogVisible.value = false
}

function cancel() {
  dialogVisible.value = false
}

function handleClosed() {
  localSql.value = props.sql || ""
  previewData.value = []
  previewColumns.value = []
  previewDone.value = false
  columns.value = []
  errorHint.value = ""
}
</script>

<style scoped>
.sql-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sql-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.sql-error-hint {
  font-size: 12px;
  color: var(--accent-rose);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* SQL 编辑器 */
.sql-editor-wrap {
  display: flex;
  border: 1px solid var(--border-primary);
  border-radius: var(--radius-sm);
  background: var(--bg-input);
  max-height: 300px;
  overflow: hidden;
  position: relative;
}
.sql-editor-wrap.has-error {
  border-color: var(--accent-rose);
  box-shadow: 0 0 0 1px var(--accent-rose-dim);
}
.sql-line-numbers {
  flex-shrink: 0;
  width: 40px;
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-subtle);
  padding: 8px 0;
  text-align: center;
  overflow: hidden;
  user-select: none;
}
.sql-line-num {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
  min-height: 1.6em;
}
.sql-textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: vertical;
  background: transparent;
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  padding: 8px 12px;
  min-height: 180px;
  tab-size: 2;
}
.sql-textarea::placeholder {
  color: var(--text-muted);
}

/* 错误栏 */
.sql-error-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--accent-rose-dim);
  color: var(--accent-rose);
  border-radius: var(--radius-sm);
  font-size: 13px;
}

/* 预览区域 */
.sql-preview-area {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.sql-preview-header {
  padding: 6px 10px;
  font-size: 12px;
  color: var(--text-muted);
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-subtle);
}
.sql-preview-empty {
  padding: 20px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
}

/* 列信息 */
.sql-columns-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 0;
}
.sql-columns-label {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}
.sql-column-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: var(--accent-dim);
  border-radius: 10px;
  font-size: 12px;
}
.sql-col-name {
  color: var(--accent);
  font-family: var(--font-mono);
}
.sql-col-type {
  color: var(--text-muted);
  font-size: 11px;
}

:deep(.el-dialog__body) {
  padding: 16px 20px;
}
</style>
