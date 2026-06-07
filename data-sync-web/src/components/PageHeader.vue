<template>
  <div class="page-header">
    <div class="page-header-title">
      <slot name="title" />
    </div>
    <div class="page-header-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header-title h2 {
  margin: 0;
}
.page-header-actions {
  display: flex;
  gap: 8px;
}
</style>
