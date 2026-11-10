<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { monitor, series } from '../monitor'
import { formatBytes, formatDuration, formatPercent, formatRate, formatTime, qualityLabel } from '../format'

const snapshot = computed(() => monitor.snapshot)
const stale = computed(() => snapshot.value?.quality === 'STALE' || snapshot.value?.quality === 'UNAVAILABLE')
const cpuChart = ref<HTMLDivElement>()
const memChart = ref<HTMLDivElement>()
const netChart = ref<HTMLDivElement>()
const diskChart = ref<HTMLDivElement>()
const charts: echarts.ECharts[] = []

const mountColumns = [
  { title: '挂载点', dataIndex: 'mount' },
  { title: '容量', customRender: ({ record }: { record: { totalBytes?: number } }) => formatBytes(record.totalBytes) },
  { title: '已用', customRender: ({ record }: { record: { usedBytes?: number } }) => formatBytes(record.usedBytes) },
  { title: '使用率', customRender: ({ record }: { record: { usedPct?: number } }) => formatPercent(record.usedPct) }
]

function render() {
  const data = series()
  const pairs: Array<[echarts.ECharts | undefined, Array<number | null>]> = [
    [charts[0], data.cpu.map(numberOrNull)],
    [charts[1], data.memory.map(numberOrNull)],
    [charts[2], data.net.map(numberOrNull)],
    [charts[3], data.disk.map(numberOrNull)]
  ]
  pairs.forEach(([chart, values]) => {
    chart?.setOption({
      backgroundColor: 'transparent',
      animation: false,
      grid: { left: 56, right: 16, top: 16, bottom: 32 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: data.labels, axisLabel: { color: '#9fb0c3', hideOverlap: true } },
      yAxis: { type: 'value', axisLabel: { color: '#9fb0c3' } },
      series: [{ type: 'line', showSymbol: false, connectNulls: false, data: values, color: '#3cbaa2' }]
    })
  })
}

function numberOrNull(value: number) {
  return Number.isNaN(value) ? null : value
}

function resize() {
  charts.forEach((chart) => chart.resize())
}

onMounted(() => {
  ;[cpuChart.value, memChart.value, netChart.value, diskChart.value].forEach((element) => {
    if (element) {
      charts.push(echarts.init(element))
    }
  })
  render()
  window.addEventListener('resize', resize)
})
onUnmounted(() => {
  window.removeEventListener('resize', resize)
  charts.forEach((chart) => chart.dispose())
})
watch(() => monitor.snapshot?.sequence, render)
</script>

<template>
  <div class="page">
    <a-alert v-if="!snapshot" type="info" show-icon message="正在等待第一份采集快照" />
    <a-alert
      v-else-if="stale"
      type="warning"
      show-icon
      :message="`数据过期，最后采集时间 ${formatTime(snapshot.observedAt)}`"
    />
    <a-card v-if="snapshot" size="small">
      <div class="status-row">
        <a-tag>{{ qualityLabel(snapshot.quality) }}</a-tag>
        <span>{{ snapshot.host.hostname || '主机名缺失' }}</span>
        <span>{{ snapshot.host.osName }} {{ snapshot.host.osVersion }}</span>
        <span>{{ snapshot.host.cpuModel || 'CPU 型号缺失' }} / {{ snapshot.host.logicalCpus ?? '未知' }} 逻辑核心</span>
        <span>JDK {{ snapshot.host.jdkVersion }}</span>
        <span>系统已运行 {{ formatDuration(snapshot.host.systemUptimeSeconds) }}</span>
      </div>
    </a-card>
    <div class="metric-grid">
      <a-card title="CPU" size="small">
        <div class="value">{{ formatPercent(snapshot?.cpu.overallBusyPct) }}</div>
        <div>用户 {{ formatPercent(snapshot?.cpu.userPct) }} / 系统 {{ formatPercent(snapshot?.cpu.systemPct) }}</div>
        <div>空闲 {{ formatPercent(snapshot?.cpu.idlePct) }} / IO {{ formatPercent(snapshot?.cpu.iowaitPct) }} / steal {{ formatPercent(snapshot?.cpu.stealPct) }}</div>
        <div>负载 {{ snapshot?.cpu.load1 ?? '无数据' }} / {{ snapshot?.cpu.load5 ?? '无数据' }} / {{ snapshot?.cpu.load15 ?? '无数据' }}</div>
      </a-card>
      <a-card title="内存" size="small">
        <div class="value">{{ formatBytes(snapshot?.memory.usedBytes) }}</div>
        <div>总量 {{ formatBytes(snapshot?.memory.totalBytes) }}，可用 {{ formatBytes(snapshot?.memory.availableBytes) }}</div>
        <div>缓存参考 {{ formatBytes(snapshot?.memory.cacheBytes) }}</div>
        <div>交换区 {{ formatBytes(snapshot?.memory.swapUsedBytes) }} / {{ formatBytes(snapshot?.memory.swapTotalBytes) }}</div>
      </a-card>
      <a-card title="网络" size="small">
        <div class="value">{{ formatRate(snapshot?.network.rxBytesPerSec) }}</div>
        <div>发送 {{ formatRate(snapshot?.network.txBytesPerSec) }}</div>
        <div>接口 {{ snapshot?.network.interfaces?.length ?? 0 }} 个，已排除回环与桥接重复项</div>
      </a-card>
      <a-card title="JVM" size="small">
        <div class="value">JVM 堆使用 {{ formatBytes(snapshot?.jvm.heapUsedBytes) }}</div>
        <div>进程驻留内存 {{ formatBytes(snapshot?.jvm.rssBytes) }}</div>
        <div>进程 CPU {{ formatPercent(snapshot?.jvm.processCpuPct) }}</div>
        <div>线程 {{ snapshot?.jvm.threadCount ?? '无数据' }}，GC {{ snapshot?.jvm.gcCount ?? '无数据' }} 次</div>
      </a-card>
    </div>
    <div class="chart-grid">
      <a-card title="CPU 使用率" size="small"><div ref="cpuChart" class="chart" /></a-card>
      <a-card title="内存使用率" size="small"><div ref="memChart" class="chart" /></a-card>
      <a-card title="网络吞吐" size="small"><div ref="netChart" class="chart" /></a-card>
      <a-card title="磁盘吞吐" size="small"><div ref="diskChart" class="chart" /></a-card>
    </div>
    <a-card title="磁盘与挂载" size="small">
      <a-table size="small" :pagination="false" :scroll="{ x: 720 }" :data-source="snapshot?.mounts ?? []" :columns="mountColumns" row-key="mount" />
    </a-card>
  </div>
</template>

<style scoped>
.value {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 8px;
}
</style>
