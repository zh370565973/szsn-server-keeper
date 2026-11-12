<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { api, type ResourceStatus, type SettingsView } from '../api'
import { reasonLabel, switchLabel } from '../labels'
import { monitor } from '../monitor'

const MIB = 1024 * 1024

const THRESHOLD_LABELS: Record<string, string> = {
  cpuTargetDefaultPct: '默认 CPU 目标',
  cpuTargetMinPct: 'CPU 目标下限',
  cpuTargetMaxPct: 'CPU 目标上限',
  cpuHardMaxPct: 'CPU 硬上限',
  cpuDeadbandPp: 'CPU 死区',
  memoryBlockBytes: '内存块大小',
  memoryGrowthBytesPerSec: '内存增长速度',
  memoryTaskCapBytes: '单任务内存上限',
  systemMemoryTargetDefaultPct: '默认系统内存目标',
  systemReserve: '系统预留',
  heapLoadHardCap: '堆负载硬上限',
  jvmReserve: 'JVM 预留',
  guardBytes: '保护余量',
  historyBucketSeconds: '历史采样间隔',
  historyRetentionDays: '监控曲线保留',
  executionRetentionDays: '执行记录保留',
  executionRecordCap: '执行记录条数上限',
  operationLogRetentionDays: '操作日志保留',
  operationLogCap: '操作日志条数上限',
  retentionBatchRows: '每批清理行数',
  maxSseConnections: '实时连接上限',
}

const settings = ref<SettingsView>()
const status = ref<ResourceStatus>()
const error = ref('')
const busy = ref(false)
const healthySeconds = computed(() => Math.floor((status.value?.healthyForMs ?? 0) / 1000))
const thresholdRows = computed(() => {
  const raw = settings.value?.thresholds ?? {}
  const known = Object.keys(THRESHOLD_LABELS).filter((key) => key in raw)
  const extra = Object.keys(raw).filter((key) => !(key in THRESHOLD_LABELS))
  return [...known, ...extra].map((key) => ({
    key,
    label: THRESHOLD_LABELS[key] ?? key,
    text: formatThreshold(key, raw[key]),
  }))
})

function formatBytes(value: number) {
  const mib = value / MIB
  if (Number.isInteger(mib) && mib >= 1024 && mib % 1024 === 0) return `${mib / 1024} GiB`
  if (Number.isInteger(mib)) return `${mib} MiB`
  return `${value} 字节`
}

function formatCount(value: number) {
  return value % 10000 === 0 ? `${value / 10000} 万条` : `${value} 条`
}

function formatThreshold(key: string, value: string | number | boolean) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'string') return value
  switch (key) {
    case 'cpuTargetDefaultPct':
    case 'cpuTargetMinPct':
    case 'cpuTargetMaxPct':
    case 'cpuHardMaxPct':
    case 'systemMemoryTargetDefaultPct':
      return `${value}%`
    case 'cpuDeadbandPp':
      return `${value} 个百分点`
    case 'memoryBlockBytes':
    case 'memoryTaskCapBytes':
    case 'guardBytes':
      return formatBytes(value)
    case 'memoryGrowthBytesPerSec':
      return `${formatBytes(value)}/秒`
    case 'historyBucketSeconds':
      return `${value} 秒`
    case 'historyRetentionDays':
    case 'executionRetentionDays':
    case 'operationLogRetentionDays':
      return `${value} 天`
    case 'executionRecordCap':
    case 'operationLogCap':
      return formatCount(value)
    case 'retentionBatchRows':
      return `${value} 行`
    case 'maxSseConnections':
      return `${value} 个`
    default:
      return String(value)
  }
}

onMounted(load)

async function load() {
  try {
    settings.value = await api<SettingsView>('/settings')
    status.value = await api<ResourceStatus>('/resource/status')
    error.value = ''
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法读取设置'
  }
}

async function setSwitch(enabled: boolean) {
  busy.value = true
  error.value = ''
  try {
    await api('/resource/control-switch', { method: 'PUT', body: JSON.stringify({ enabled }) })
    message.success(enabled ? '控制开关已打开。这不会启动负载。' : '控制开关已关闭')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '开关更新失败'
  } finally {
    busy.value = false
  }
}

async function resetLatch() {
  busy.value = true
  error.value = ''
  try {
    await api('/resource/reset-latch', { method: 'POST' })
    message.success('锁存已解除，控制开关仍为关闭')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '不能解除锁存'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page page-fill">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <div class="split settings-top">
      <a-card title="控制开关" size="small">
        <p class="hint">当前 {{ switchLabel(status?.controlSwitch ?? settings?.controlSwitch) }}。{{ settings?.controlSwitchPolicy }}</p>
        <p v-if="status?.emergencyLatched" class="hint latch-note">
          已锁存，原因 {{ reasonLabel(status.latchReason) }}。关键指标已连续健康 {{ healthySeconds }} 秒，解除需要 30 秒，且没有活动会话。
        </p>
        <p v-else class="hint latch-note">当前没有急停锁存。打开开关只允许以后启动，不会补跑计划，也不会启动引擎。</p>
        <a-button :loading="busy" @click="setSwitch((status?.controlSwitch ?? 'OFF') !== 'ON')">
          {{ (status?.controlSwitch ?? 'OFF') === 'ON' ? '关闭控制' : '打开控制' }}
        </a-button>
        <a-tooltip :title="status?.emergencyLatched ? '直接解除，不满足条件时会拒绝' : '当前没有急停锁存'">
          <a-button :disabled="!status?.emergencyLatched" :loading="busy" style="margin-left: 8px" @click="resetLatch">
            解除锁存
          </a-button>
        </a-tooltip>
      </a-card>
      <a-card title="部署位置" size="small">
        <p class="hint">config 指向实际读取的配置文件，改完后重启生效。网页不能修改。</p>
        <a-descriptions bordered size="small" :column="2" class="block">
          <a-descriptions-item label="监听">{{ settings?.listen || '—' }}</a-descriptions-item>
          <a-descriptions-item label="时区">{{ settings?.timezone || '—' }}</a-descriptions-item>
          <a-descriptions-item v-for="(value, key) in settings?.deploy ?? {}" :key="key" :label="String(key)">
            {{ value }}
          </a-descriptions-item>
          <a-descriptions-item label="存储" :span="2">
            {{ monitor.info?.storageHealthy === false ? monitor.info?.storageFault : '可写' }}
          </a-descriptions-item>
        </a-descriptions>
      </a-card>
    </div>
    <a-card title="只读阈值" size="small" class="grow-card">
      <p class="hint block-note">
        监控曲线保留 7 天。过期后页面不再绘制那段曲线，执行摘要继续留在执行记录里。执行记录保留 90 天、最多 10 万条；操作日志保留 30 天、最多 20 万条。每小时分批清理，每批最多 1000 行，活动中的实例不删除。
      </p>
      <a-descriptions bordered size="small" :column="2">
        <a-descriptions-item v-for="row in thresholdRows" :key="row.key" :label="row.label">
          {{ row.text }}
        </a-descriptions-item>
      </a-descriptions>
    </a-card>
  </div>
</template>

<style scoped>
.settings-top {
  align-items: stretch;
}
.settings-top > :deep(.ant-card) {
  height: 100%;
}
.latch-note,
.block-note {
  margin-bottom: 8px;
}
.block {
  margin-top: 8px;
}
</style>
