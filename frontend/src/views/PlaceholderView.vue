<script setup lang="ts">
defineProps<{ title: string; phase: string }>()
</script>

<template>
  <a-card>
    <a-empty :description="`${title}将在 ${phase} 交付。当前不能创建、修改或运行。`" />
  </a-card>
</template>
