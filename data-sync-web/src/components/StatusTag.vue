<template>
  <span class="status-tag" :class="statusClass">
    <span class="status-dot" :class="statusClass"></span>
    <span class="status-label">{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue"
import { STATUS_MAP, SYNC_MODE_MAP, TRIGGER_MAP } from "@/types"

const props = defineProps<{ value: string }>()

const statusClass = computed(() => {
  if (props.value in STATUS_MAP) {
    const t = STATUS_MAP[props.value].type
    if (t === "success") return "success"
    if (t === "danger") return "danger"
    if (t === "warning") return "warning"
    return "info"
  }
  return "info"
})

const label = computed(() => {
  if (props.value in STATUS_MAP) return STATUS_MAP[props.value].label
  if (props.value in SYNC_MODE_MAP) return SYNC_MODE_MAP[props.value]
  if (props.value in TRIGGER_MAP) return TRIGGER_MAP[props.value]
  return props.value
})
</script>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 10px 2px 6px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
}
.status-tag.success {
  background: var(--accent-emerald-dim);
  color: var(--accent-emerald);
}
.status-tag.danger {
  background: var(--accent-rose-dim);
  color: var(--accent-rose);
}
.status-tag.warning {
  background: var(--accent-amber-dim);
  color: var(--accent-amber);
}
.status-tag.info {
  background: rgba(148, 163, 184, 0.1);
  color: var(--text-secondary);
}
.status-tag.primary {
  background: var(--accent-teal-dim);
  color: var(--accent-teal);
}
.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.status-tag.success .status-dot { background: var(--accent-emerald); box-shadow: 0 0 4px var(--accent-emerald-dim); }
.status-tag.danger .status-dot { background: var(--accent-rose); box-shadow: 0 0 4px var(--accent-rose-dim); }
.status-tag.warning .status-dot { background: var(--accent-amber); box-shadow: 0 0 4px var(--accent-amber-dim); }
.status-tag.info .status-dot { background: var(--text-muted); }
.status-tag.primary .status-dot { background: var(--accent-teal); box-shadow: 0 0 4px var(--accent-teal-dim); }
</style>
