import { ref, watch, type Ref } from "vue"

/**
 * 分页通用 composable
 * @param fetcher 接收 (page, size) 返回 Promise<PageRes<T>> 的加载函数
 */
export function usePagination<T>(
  fetcher: (page: number, size: number) => Promise<{ content: T[]; totalElements: number }>,
) {
  const data = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const total = ref(0)
  const page = ref(1)
  const size = ref(10)

  async function load() {
    loading.value = true
    try {
      const res = await fetcher(page.value - 1, size.value)
      data.value = res.content || []
      total.value = res.totalElements || 0
    } catch {
      data.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  watch([page, size], load, { immediate: false })

  function resetPage() {
    page.value = 1
    load()
  }

  return { data, loading, total, page, size, load, resetPage }
}
