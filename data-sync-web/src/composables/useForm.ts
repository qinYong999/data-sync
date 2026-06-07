import { ref } from "vue"
import { ElMessage } from "element-plus"

interface UseFormOptions<T, S> {
  isEdit: boolean
  id: number | string
  fetch: (id: number) => Promise<T>
  save: (data: S) => Promise<{ id: number }>
  update: (id: number, data: Partial<S>) => Promise<any>
  onSaved: () => void
}

/**
 * 表单通用 composable（新增 / 编辑模式）
 */
export function useForm<T extends { id?: number }, S = any>(
  opts: UseFormOptions<T, S>,
) {
  const saving = ref(false)
  const loading = ref(false)

  async function loadData(): Promise<T | null> {
    if (!opts.isEdit) return null
    loading.value = true
    try {
      return await opts.fetch(Number(opts.id))
    } finally {
      loading.value = false
    }
  }

  async function handleSave(formData: S) {
    saving.value = true
    try {
      if (opts.isEdit) {
        await opts.update(Number(opts.id), formData)
      } else {
        await opts.save(formData)
      }
      ElMessage.success("保存成功")
      opts.onSaved()
    } catch (e: any) {
      // 全局 interceptor 已 toast，组件可自定义额外处理
      throw e
    } finally {
      saving.value = false
    }
  }

  return { saving, loading, loadData, handleSave }
}
