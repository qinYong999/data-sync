<template>
  <div v-if="visible" class="empty-state">
    <div class="empty-icon">{{ icon }}</div>
    <div class="empty-text">{{ description }}</div>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean; description?: string; icon?: string }>()
</script>
