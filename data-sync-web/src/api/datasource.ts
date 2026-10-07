import api, { ApiError } from "./request"
import type { DataSourceVO, DataSourceForm, PageRes, ColumnInfo, TestDetailVO } from "@/types"

/**
 * 列信息端点：契约 §4.3 新增 `GET /api/datasources/{id}/tables/{table}/columns`，
 * 既有实现是 `GET /api/datasources/{id}/columns?table=`。两者都支持，探测一次后固定使用。
 */
type ColumnsMode = "new" | "legacy" | null
let columnsMode: ColumnsMode = null

async function fetchColumns(id: number, table: string): Promise<ColumnInfo[]> {
  const legacy = () =>
    api.get<ColumnInfo[]>("/datasources/" + id + "/columns", { params: { table } })

  const modern = () =>
    api.get<ColumnInfo[]>(
      "/datasources/" + id + "/tables/" + encodeURIComponent(table) + "/columns",
    )

  const order: Array<"new" | "legacy"> =
    columnsMode === "new"
      ? ["new", "legacy"]
      : columnsMode === "legacy"
        ? ["legacy", "new"]
        : ["new", "legacy"]

  let lastError: unknown = null
  for (const mode of order) {
    try {
      const res = mode === "new" ? await modern() : await legacy()
      columnsMode = mode
      return Array.isArray(res) ? res : []
    } catch (e) {
      lastError = e
      // 只有 404/405 说明端点形态不对；其它错误（权限、连接失败）应直接暴露
      if (!(e instanceof ApiError) || (e.status !== 404 && e.status !== 405)) throw e
    }
  }
  if (lastError) throw lastError
  return []
}

/**
 * 构造提交给后端的数据源载荷。
 * 编辑态下**口令留空即不下发 password 字段**（后端据此保留原口令），避免用空串覆盖。
 */
export function buildDataSourcePayload(
  form: DataSourceForm,
  isEdit: boolean,
): Partial<DataSourceForm> {
  const payload: Partial<DataSourceForm> = {
    name: form.name,
    dbType: form.dbType,
    host: form.host,
    port: form.port,
    databaseName: form.databaseName,
    username: form.username,
  }
  const pwd = (form.password || "").trim()
  if (!isEdit || pwd) {
    payload.password = form.password
  }
  return payload
}

export const datasourceApi = {
  list: (params?: unknown) => api.get<PageRes<DataSourceVO>>("/datasources", { params }),
  get: (id: number) => api.get<DataSourceVO>("/datasources/" + id),
  create: (data: Partial<DataSourceForm>) => api.post<DataSourceVO>("/datasources", data),
  update: (id: number, data: Partial<DataSourceForm>) =>
    api.put<DataSourceVO>("/datasources/" + id, data),
  delete: (id: number) => api.delete<void>("/datasources/" + id),
  /** 用库内已存口令测试（用于编辑态，前端不需要也不应该拿到明文） */
  test: (id: number) => api.post<boolean>("/datasources/" + id + "/test"),
  testDirect: (data: Partial<DataSourceForm>) => api.post<boolean>("/datasources/test", data),

  /**
   * 带中文失败原因的连接测试（平台新增端点，口令已清洗，可直接展示）。
   * 端点不存在时回退到裸 boolean 版本，保证与旧后端兼容。
   */
  testDetail: async (id: number): Promise<TestDetailVO> => {
    try {
      const raw = await api.post<TestDetailVO>(
        "/datasources/" + id + "/test-detail",
        undefined,
        { silent: true, validateStatus: (s) => s < 500 },
      )
      if (raw && typeof raw === "object" && typeof raw.success === "boolean") return raw
    } catch (e) {
      if (!(e instanceof ApiError) || (e.status !== 404 && e.status !== 405)) throw e
    }
    const ok = await datasourceApi.test(id)
    return { success: !!ok, message: ok ? "连接成功" : "连接失败（请检查主机、账号与密码）" }
  },

  testDetailDirect: async (data: Partial<DataSourceForm>): Promise<TestDetailVO> => {
    try {
      const raw = await api.post<TestDetailVO>("/datasources/test-detail", data, {
        silent: true,
        validateStatus: (s) => s < 500,
      })
      if (raw && typeof raw === "object" && typeof raw.success === "boolean") return raw
    } catch (e) {
      if (!(e instanceof ApiError) || (e.status !== 404 && e.status !== 405)) throw e
    }
    const ok = await datasourceApi.testDirect(data)
    return { success: !!ok, message: ok ? "连接成功" : "连接失败（请检查主机、账号与密码）" }
  },

  getTables: async (id: number): Promise<string[]> => {
    const res = await api.get<string[]>("/datasources/" + id + "/tables")
    return Array.isArray(res) ? res : []
  },
  getTableColumns: fetchColumns,
  getSqlColumns: (id: number, sql: string) =>
    api.post<ColumnInfo[]>("/datasources/" + id + "/sql-columns", { sql }),
  previewSql: (id: number, sql: string, limit?: number) =>
    api.post<Record<string, unknown>[]>("/datasources/" + id + "/sql-preview", {
      sql,
      limit: limit || 5,
    }),
}
