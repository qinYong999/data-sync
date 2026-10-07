/**
 * 轻量并发控制（避免一次性打爆后端/数据库）
 */

export interface ConcurrencyOptions {
  /** 最大并发数，默认 3 */
  limit?: number
}

/**
 * 以受限并发对 items 执行 worker。
 * worker 抛出的异常会被捕获并忽略（由 worker 自行处理错误展示）。
 */
export async function runWithConcurrency<T>(
  items: T[],
  worker: (item: T, index: number) => Promise<void>,
  options: ConcurrencyOptions = {},
): Promise<void> {
  const limit = Math.max(1, options.limit ?? 3)
  let cursor = 0

  async function runner() {
    for (;;) {
      const index = cursor++
      if (index >= items.length) return
      try {
        await worker(items[index], index)
      } catch {
        /* 单个失败不影响其它 */
      }
    }
  }

  const runners: Promise<void>[] = []
  const n = Math.min(limit, items.length)
  for (let i = 0; i < n; i++) runners.push(runner())
  await Promise.all(runners)
}

/** 简易延迟 */
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
