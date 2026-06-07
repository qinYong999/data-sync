import { ElMessageBox } from "element-plus"

/**
 * 通用删除确认对话框
 * @example const confirm = useConfirm()
 * if (await confirm('确定删除该数据源？')) { ... }
 */
export function useConfirm() {
  return async function confirm(
    message: string,
    title = "确认",
  ): Promise<boolean> {
    try {
      await ElMessageBox.confirm(message, title, {
        type: "warning",
        confirmButtonText: "确定",
        cancelButtonText: "取消",
      })
      return true
    } catch {
      return false
    }
  }
}
