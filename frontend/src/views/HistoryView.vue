<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type HistoryView } from '../api'
import { useTableBodyHeight } from '../fillHeight'
import { formatBytes, formatPercent, formatTime } from '../format'

const DAY = 24 * 60 * 60 * 1000
const presets = [
  { label: '1 小时', ms: 60 * 60 * 1000 },
  { label: '24 小时', ms: DAY },
  { label: '7 天内', ms: 7 * DAY - 60_000 },
  { label: '含过期', ms: 8 * DAY }
]
const tableBox = ref<HTMLElement>()
const tableBodyHeight = useTableBodyHeight(tableBox)
const loading = ref(false)
const error = ref('')
const view = ref<HistoryView>()
const selected = ref(presets[0].label)
const range = ref<[number, number]>([Date.now() - presets[0].ms, Date.now()])

const columns = [
  { title: '桶起始', dataIndex: 'bucketStart', customRender: ({ value }: { value: number }) => formatTime(value) },
  { title: '样本数', dataIndex: 'sampleCount' },
  { title: '覆盖毫秒', dataIndex: 'coverageMs' },
  { title: 'CPU 均值', customRender: ({ record }: { record: { metrics: Record<string, number> } }) => formatPercent(record.metrics.cpuBusyAvg) },
  { title: 'CPU 峰值', customRender: ({ record }: { record: { metrics: Record<string, number> } }) => formatPercent(record.metrics.cpuBusyPeak) },
  { title: '最小可用内存', customRender: ({ record }: { record: { metrics: Record<string, number> } }) => formatBytes(record.metrics.memAvailableMinBytes) }
]

function selectRange(preset: { label: string; ms: number }) {
  selected.value = preset.label
  range.value = [Date.now() - preset.ms, Date.now()]
  load()
}

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
  <div class="page page-fill">
    <a-card size="small" class="shrink">
      <div class="status-row">
        <span class="hint">服务端自动限制在 2000 个桶以内。监控曲线保留 7 天，过期后不再显示曲线，执行记录里的摘要仍然保留。</span>
        <a-button
          v-for="preset in presets"
          :key="preset.label"
          size="small"
          :type="selected === preset.label ? 'primary' : 'default'"
          @click="selectRange(preset)"
        >
          {{ preset.label }}
        </a-button>
        <a-button type="primary" size="small" :loading="loading" @click="load">刷新</a-button>
      </div>
      <p v-if="view" class="hint bucket-note">实际桶宽 {{ view.bucketSeconds }} 秒。没有返回的时间段表示没有有效样本。</p>
    </a-card>
    <a-alert
      v-if="view?.pastRetention"
      type="warning"
      show-icon
      message="查询起点已超过 7 天曲线保留期。过期曲线已清理，执行摘要仍留在执行记录中。"
    />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <div ref="tableBox" class="table-fill">
      <a-table
        bordered
        size="small"
        :loading="loading"
        :columns="columns"
        :data-source="view?.buckets ?? []"
        :pagination="{ pageSize: 20, size: 'small' }"
        :scroll="{ x: 900, y: tableBodyHeight }"
        row-key="bucketStart"
      >
        <template #emptyText>这段时间没有有效聚合数据</template>
      </a-table>
    </div>
  </div>
</template>

<style scoped>
.bucket-note {
  margin-top: 6px;
}
</style>
