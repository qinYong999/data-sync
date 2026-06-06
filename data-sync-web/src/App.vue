<template>
  <div id="app-container">
    <el-container>
      <el-aside width="220px">
        <el-menu :default-active="route.path" router background-color="#304156" text-color="#bfcbd9" active-text-color="#409eff">
          <div class="logo">数据同步平台</div>
          <el-menu-item index="/">
            <el-icon><Monitor /></el-icon><span>仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/datasources">
            <el-icon><Connection /></el-icon><span>数据源管理</span>
          </el-menu-item>
          <el-menu-item index="/tasks">
            <el-icon><List /></el-icon><span>同步任务</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">数据同步管理平台</el-header>
        <el-main><router-view /></el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from "vue-router"
import { Monitor, Connection, List } from "@element-plus/icons-vue"
const route = useRoute()
</script>

<style>
body { margin: 0; font-family: "Helvetica Neue", Helvetica, "PingFang SC", sans-serif; }
#app-container { height: 100vh; }
.el-aside { background-color: #304156; }
.logo { height: 60px; line-height: 60px; text-align: center; color: #fff; font-size: 16px; font-weight: bold; }
.header { background: #fff; border-bottom: 1px solid #e6e6e6; display: flex; align-items: center; font-size: 16px; color: #333; padding: 0 20px; }
</style>