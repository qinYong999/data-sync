<template>
  <el-empty v-if="visible" :description="description" :image-size="100">
    <template v-if="$slots.default">
      <slot />
    </template>
  </el-empty>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean; description?: string }>()
</script>
