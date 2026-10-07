import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router"
import { ElMessage } from "element-plus"
import { ensureSession, markUnauthenticated, useAuth } from "@/composables/useAuth"
import { setOnLoginPage, setUnauthorizedHandler } from "@/utils/authBus"

const routes: RouteRecordRaw[] = [
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/Login.vue"),
    meta: { public: true, blank: true, title: "登录" },
  },
  { path: "/", name: "Dashboard", component: () => import("@/views/Dashboard.vue") },
  { path: "/datasources", name: "Datasources", component: () => import("@/views/DataSourceList.vue") },
  { path: "/datasources/new", name: "NewDatasource", component: () => import("@/views/DataSourceForm.vue") },
  { path: "/datasources/:id/edit", name: "EditDatasource", component: () => import("@/views/DataSourceForm.vue") },
  { path: "/tasks", name: "Tasks", component: () => import("@/views/TaskList.vue") },
  { path: "/tasks/new", name: "NewTask", component: () => import("@/views/TaskForm.vue") },
  { path: "/tasks/:id/edit", name: "EditTask", component: () => import("@/views/TaskForm.vue") },
  { path: "/tasks/:id/records", name: "TaskRecords", component: () => import("@/views/TaskRecords.vue") },
  { path: "/:pathMatch(.*)*", redirect: "/" },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/** axios 拦截器检测到未认证 / 会话失效时，由路由层统一跳转（避免循环依赖） */
setUnauthorizedHandler((reason) => {
  markUnauthenticated()
  const current = router.currentRoute.value
  if (current.path === "/login") return
  ElMessage.warning(reason || "登录已过期，请重新登录")
  const redirect = current.fullPath && current.fullPath !== "/" ? current.fullPath : undefined
  router.replace({ path: "/login", query: redirect ? { redirect } : {} })
})

router.beforeEach(async (to) => {
  setOnLoginPage(to.path === "/login")

  const session = await ensureSession()

  // 登录页：仅在"确认已登录"时才跳走；后端不可达/未启用安全时留在登录页
  if (to.meta.public) {
    if (session.authenticated && session.securityEnabled === true && session.username) {
      const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/"
      return redirect
    }
    return true
  }

  // 业务页：只有确认后端启用了安全且确实未登录时才拦截
  if (!session.authenticated && session.securityEnabled !== false) {
    return { path: "/login", query: { redirect: to.fullPath } }
  }
  return true
})

/** 顶栏退出登录后由 App.vue 调用 */
export async function redirectToLoginAfterLogout(): Promise<void> {
  const auth = useAuth()
  auth.markUnauthenticated()
  await router.replace({ path: "/login" })
}

export default router
