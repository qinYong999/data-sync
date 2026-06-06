import { createRouter, createWebHistory } from "vue-router"
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "Dashboard", component: () => import("@/views/Dashboard.vue") },
    { path: "/datasources", name: "Datasources", component: () => import("@/views/DataSourceList.vue") },
    { path: "/datasources/new", name: "NewDatasource", component: () => import("@/views/DataSourceForm.vue") },
    { path: "/datasources/:id/edit", name: "EditDatasource", component: () => import("@/views/DataSourceForm.vue") },
    { path: "/tasks", name: "Tasks", component: () => import("@/views/TaskList.vue") },
    { path: "/tasks/new", name: "NewTask", component: () => import("@/views/TaskForm.vue") },
    { path: "/tasks/:id/edit", name: "EditTask", component: () => import("@/views/TaskForm.vue") },
    { path: "/tasks/:id/records", name: "TaskRecords", component: () => import("@/views/TaskRecords.vue") },
  ]
})
export default router