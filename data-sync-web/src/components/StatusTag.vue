<template>
  <span class="status-tag" :class="statusClass">
    <span class="status-dot" :class="statusClass"></span>
    {{ label }}
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
