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

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 56px 24px;
  text-align: center;
}
.empty-icon {
  font-size: 48px;
  line-height: 1;
  margin-bottom: 16px;
  opacity: 0.25;
}
.empty-text {
  font-size: 15px;
  color: var(--text-muted);
  max-width: 360px;
  line-height: 1.6;
}
.empty-action {
  margin-top: 20px;
}
</style>
