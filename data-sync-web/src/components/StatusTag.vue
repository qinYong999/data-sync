<template>
  <el-tag :type="tagType" size="small">
    {{ label }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from "vue"
import { STATUS_MAP, SYNC_MODE_MAP, TRIGGER_MAP } from "@/types"

const props = defineProps<{ value: string; map?: string }>()

const tagType = computed(() => {
  // 优先使用 STATUS_MAP，然后 fallback
  const entry = STATUS_MAP[props.value]
  if (entry) return entry.type
  return "info"
})

const label = computed(() => {
  if (props.value in STATUS_MAP) return STATUS_MAP[props.value].label
  if (props.value in SYNC_MODE_MAP) return SYNC_MODE_MAP[props.value]
  if (props.value in TRIGGER_MAP) return TRIGGER_MAP[props.value]
  return props.value
})
</script>
