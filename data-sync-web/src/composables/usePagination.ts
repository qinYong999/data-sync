import { ref, watch, type Ref } from "vue"
import { toApiError } from "@/api/request"
import { normalizePage } from "@/utils/page"
import type { PageRes } from "@/types"

export interface UsePaginationOptions {
  /** 初始每页条数（默认 10） */
  size?: number
  /** 每页条数可选项（默认 [10, 20, 50, 100]） */
  sizes?: number[]
}

/**
 * 分页通用 composable
 *
 * @param fetcher 接收 (page, size) —— **page 从 0 开始** —— 返回分页响应。
 *                既可返回 Spring Data `Page`，也可返回裸数组（内部自动规整）。
 *
 * 失败时不抛出，而是把中文错误写入 `error`，由视图决定如何展示；
 * 表格数据与总数会被清空，避免残留上一次的结果造成误导。
 */
export function usePagination<T>(
  fetcher: (page: number, size: number) => Promise<PageRes<T> | T[]>,
  options: UsePaginationOptions = {},
) {
  const data = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1) // el-pagination 是 1 起
  const size = ref(options.size ?? 10)
  const sizes = options.sizes ?? [10, 20, 50, 100]
  const error = ref("")

  /** @returns true = 加载成功 */
  async function load(): Promise<boolean> {
    loading.value = true
    error.value = ""
    try {
      const res = await fetcher(page.value - 1, size.value)
      const normalized = normalizePage<T>(res, page.value - 1, size.value)
      data.value = normalized.content || []
      total.value = normalized.totalElements || 0
      return true
    } catch (e) {
      data.value = []
      total.value = 0
      error.value = toApiError(e).message
      return false
    } finally {
      loading.value = false
    }
  }

  watch([page, size], () => {
    load()
  })

  function resetPage() {
    if (page.value === 1) {
      load()
    } else {
      page.value = 1 // 触发 watch
    }
  }

  return { data, loading, total, page, size, sizes, error, load, resetPage }
}
