<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type HistoryView } from '../api'
import { formatBytes, formatPercent, formatTime } from '../format'

const loading = ref(false)
const error = ref('')
const view = ref<HistoryView>()
const range = ref<[number, number]>([Date.now() - 60 * 60 * 1000, Date.now()])

const columns = [
  { title: '桶起始', dataIndex: 'bucketStart', customRender: ({ value }: { value: number }) => formatTime(value) },
  { title: '样本数', dataIndex: 'sampleCount' },
  { title: '覆盖毫秒', dataIndex: 'coverageMs' },
  { title: 'CPU 均值', customRender: ({ record }: { record: { metrics: Record<string, number> } }) => formatPercent(record.metrics.cpuBusyAvg) },
  { title: 'CPU 峰值', customRender: ({ record }: { record: { metrics: Record<string, number> } }) => formatPercent(record.metrics.cpuBusyPeak) },
  { title: '最小可用内存', customRender: ({ record }: { record: { metrics: Record<string, number> } }) => formatBytes(record.metrics.memAvailableMinBytes) }
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    view.value = await api<HistoryView>(`/monitor/history?from=${range.value[0]}&to=${range.value[1]}`)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '历史查询失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <a-card size="small">
      <div class="status-row">
        <span>最近 1 小时，服务端自动限制在 2000 个桶以内。</span>
        <a-button type="primary" :loading="loading" @click="load">刷新</a-button>
      </div>
      <p v-if="view">实际桶宽 {{ view.bucketSeconds }} 秒。没有返回的时间段表示没有有效样本。</p>
    </a-card>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table
      size="small"
      :loading="loading"
      :columns="columns"
      :data-source="view?.buckets ?? []"
      :pagination="{ pageSize: 20 }"
      :scroll="{ x: 900 }"
      row-key="bucketStart"
    >
      <template #emptyText>这段时间没有有效聚合数据</template>
    </a-table>
  </div>
</template>
