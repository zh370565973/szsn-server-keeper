<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { ApiError, api, type ResourceStatus } from '../api'
import { formatBytes, formatPercent } from '../format'
import { reasonLabel, statusLabel, switchLabel } from '../labels'
import { monitor, refreshResource } from '../monitor'

const COMBINED_TIP = '两边一起压。有一边没起来，两边一起停。'
const CPU_TARGET_TIP = '整台机器的 CPU 大概忙到这个数。别的程序已经更高时，压不下去。目标最高 90%。'
const SYSTEM_PERCENT_TIP = '整台机器的内存大概用到这个数，和 CPU 一样填百分比。本来就已经更高时，不再往上加。快到保护线时会自己停。'
const FINITE_TIP = '到点自己停。中间暂停的时间也算在里面。'
const CONTINUOUS_TIP = '不会自己停。要自己点停止，或者走急停。'
const DURATION_TIP = '最短 10 秒，最长 7 天。'

const DURATION_MIN_SECONDS = 10
const DURATION_MAX_SECONDS = 7 * 24 * 60 * 60

const status = ref<ResourceStatus>()
const error = ref('')
const busy = ref(false)
const targetPct = ref(90)
const memoryTargetPct = ref(90)
const durationSeconds = ref(300)
const runMode = ref<'FINITE' | 'CONTINUOUS'>('FINITE')
const resourceMode = ref<'CPU' | 'MEMORY' | 'COMBINED'>('CPU')
let timer: number | undefined

const reasons = computed(() => status.value?.controlDisabledReasons ?? [])
const reasonText = computed(() => reasons.value.map((item) => reasonLabel(item)).join('；') || '负载控制不可用')
const cpu = computed(() => status.value?.cpu)
const memory = computed(() => status.value?.memory)
const budget = computed(() => status.value?.budget)
const active = computed(() => status.value?.activeExecution)
const snapshot = computed(() => monitor.snapshot)
const capable = computed(() => !!status.value?.controlCapable)
const switchOn = computed(() => status.value?.controlSwitch === 'ON')
const latched = computed(() => !!status.value?.emergencyLatched)
const hasSession = computed(() => !!active.value)
const usesCpu = computed(() => resourceMode.value !== 'MEMORY')
const usesMemory = computed(() => resourceMode.value !== 'CPU')

const maxAllowedBytes = computed(() => {
  const value = budget.value?.maxAllowedBytes
  if (value != null) {
    return value
  }
  const held = budget.value?.heldBytes ?? 0
  const additional = budget.value?.additionalBytes ?? 0
  return held + additional
})
const durationOk = computed(() => {
  if (runMode.value !== 'FINITE') {
    return true
  }
  return Number.isFinite(durationSeconds.value)
    && durationSeconds.value >= DURATION_MIN_SECONDS
    && durationSeconds.value <= DURATION_MAX_SECONDS
})
const memoryPctOk = computed(() => {
  if (!usesMemory.value) {
    return true
  }
  return Number.isFinite(memoryTargetPct.value) && memoryTargetPct.value >= 1 && memoryTargetPct.value <= 90
})
const memoryReady = computed(() => memoryPctOk.value)

const canStart = computed(() =>
  capable.value &&
  switchOn.value &&
  !latched.value &&
  !hasSession.value &&
  !busy.value &&
  durationOk.value &&
  (!usesMemory.value || memoryReady.value)
)
const canPause = computed(() => hasSession.value && active.value?.status === 'RUNNING' && !busy.value)
const canResume = computed(() => hasSession.value && active.value?.status === 'PAUSED' && !busy.value)
const canStop = computed(
  () =>
    hasSession.value &&
    !['STOPPED', 'COMPLETED', 'FAILED', 'INTERRUPTED'].includes(active.value?.status ?? '') &&
    !busy.value
)
const canEditTarget = computed(() =>
  hasSession.value && ['RUNNING', 'PAUSED'].includes(active.value?.status ?? '') && !busy.value
)

const limitedText = computed(() => {
  const cpuReasons = cpu.value?.limitedReasons ?? []
  const memReasons = memory.value?.limitedReasons ?? active.value?.limitedReasons ?? []
  const items = [...cpuReasons, ...memReasons]
  return items.length ? Array.from(new Set(items.map((item) => reasonLabel(item)))).join('；') : '无'
})

const configVersion = computed(() => {
  if (!usesCpu.value) {
    return memory.value?.configVersion ?? active.value?.configVersion ?? null
  }
  return cpu.value?.configVersion ?? memory.value?.configVersion ?? active.value?.configVersion ?? null
})

const startTip = computed(() => {
  if (canStart.value) {
    return '开始压。开之前还会再查一遍。'
  }
  if (!durationOk.value) {
    return DURATION_TIP
  }
  if (usesMemory.value && !memoryPctOk.value) {
    return '整机内存占用要在 1% 到 90% 之间。'
  }
  return reasonText.value
})

const applyBlocked = computed(() => usesMemory.value && !memoryPctOk.value)

onMounted(async () => {
  await reload()
  timer = window.setInterval(() => {
    void reload().catch(() => undefined)
  }, 2000)
})
onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})

watch(
  () => monitor.resource,
  (value) => {
    if (value) {
      status.value = value
      syncFromStatus(value)
    }
  }
)

function syncFromStatus(value: ResourceStatus) {
  if (!value.activeExecution) {
    return
  }
  const cpuOn = value.cpu?.targetPct != null
  const memOn = !!value.memory?.memoryMode
  if (cpuOn && memOn) {
    resourceMode.value = 'COMBINED'
  } else if (memOn) {
    resourceMode.value = 'MEMORY'
  } else if (cpuOn) {
    resourceMode.value = 'CPU'
  }
  if (value.cpu?.targetPct != null) {
    targetPct.value = value.cpu.targetPct
  }
  if (value.memory?.memoryTargetPct != null) {
    memoryTargetPct.value = value.memory.memoryTargetPct
  }
}

async function reload() {
  try {
    status.value = await api<ResourceStatus>('/resource/status')
    monitor.resource = status.value
    error.value = ''
    syncFromStatus(status.value)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法读取控制状态'
  }
}

function newKey() {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function buildBody(): Record<string, unknown> {
  const body: Record<string, unknown> = {
    runMode: runMode.value
  }
  if (runMode.value === 'FINITE') {
    body.durationSeconds = durationSeconds.value
  }
  body.resourceMode = resourceMode.value
  if (usesCpu.value) {
    body.cpuTargetPct = targetPct.value
  }
  if (usesMemory.value) {
    body.memoryMode = 'SYSTEM_PERCENT'
    body.memoryTargetPct = memoryTargetPct.value
  }
  return body
}

async function start() {
  if (!durationOk.value) {
    error.value = `时长必须在 ${DURATION_MIN_SECONDS} 秒到 7 天之间`
    return
  }
  if (usesMemory.value && !memoryPctOk.value) {
    error.value = '系统占用目标必须在 1% 到 90% 之间'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const accepted = await api<{ executionId: string; status: string; engineCompleted?: boolean }>(
      '/executions',
      {
        method: 'POST',
        headers: { 'Idempotency-Key': newKey() },
        body: JSON.stringify(buildBody())
      }
    )
    message.success(`已受理启动，状态 ${statusLabel(accepted.status)}。实际调节以状态为准，不假定已达目标。`)
    await reload()
    await refreshResource()
  } catch (caught) {
    error.value = describe(caught)
  } finally {
    busy.value = false
  }
}

async function pause() {
  if (!active.value) {
    return
  }
  busy.value = true
  try {
    const accepted = await api<{ status: string }>(`/executions/${active.value.executionId}/pause`, { method: 'POST' })
    message.success(`已受理暂停，当前 ${statusLabel(accepted.status)}`)
    await reload()
  } catch (caught) {
    error.value = describe(caught)
  } finally {
    busy.value = false
  }
}

async function resume() {
  if (!active.value) {
    return
  }
  busy.value = true
  try {
    const accepted = await api<{ status: string }>(`/executions/${active.value.executionId}/resume`, { method: 'POST' })
    message.success(`已受理恢复，当前 ${statusLabel(accepted.status)}`)
    await reload()
  } catch (caught) {
    error.value = describe(caught)
  } finally {
    busy.value = false
  }
}

async function stop() {
  if (!active.value) {
    return
  }
  busy.value = true
  try {
    const accepted = await api<{ status: string }>(`/executions/${active.value.executionId}/stop`, { method: 'POST' })
    message.success(`已受理停止，当前 ${statusLabel(accepted.status)}`)
    await reload()
  } catch (caught) {
    error.value = describe(caught)
  } finally {
    busy.value = false
  }
}

async function applyTarget() {
  if (!active.value || configVersion.value == null) {
    return
  }
  if (usesMemory.value && !memoryPctOk.value) {
    error.value = '系统占用目标必须在 1% 到 90% 之间'
    return
  }
  busy.value = true
  try {
    const body: Record<string, unknown> = {
      expectedVersion: configVersion.value
    }
    if (usesCpu.value) {
      body.cpuTargetPct = targetPct.value
    }
    if (usesMemory.value) {
      body.memoryTargetPct = memoryTargetPct.value
    }
    const accepted = await api<{ status: string; version: number }>(`/executions/${active.value.executionId}/targets`, {
      method: 'PATCH',
      body: JSON.stringify(body)
    })
    message.success(`已受理改目标，版本 ${accepted.version}`)
    await reload()
  } catch (caught) {
    error.value = describe(caught)
  } finally {
    busy.value = false
  }
}

async function preflight() {
  busy.value = true
  try {
    const result = await api<{
      ok: boolean
      reasons?: string[]
      workerCount?: number
      maxAllowedBytes?: number
      budget?: { maxAllowedBytes?: number }
    }>('/resource/preflight', {
      method: 'POST',
      body: JSON.stringify(buildBody())
    })
    if (result.ok) {
      const workers = result.workerCount != null ? `，预计 Worker ${result.workerCount} 个` : ''
      message.success(`预检通过${workers}。预检不预留资源。`)
    } else {
      message.warning(`${(result.reasons ?? []).map((item) => reasonLabel(item)).join('；') || '预检未通过'}`)
    }
  } catch (caught) {
    error.value = describe(caught)
  } finally {
    busy.value = false
  }
}

function describe(caught: unknown) {
  if (caught instanceof ApiError) {
    if (caught.code === 'MEMORY_BUDGET_EXCEEDED') {
      return '固定内存目标超过当前预算。请改小百分比后再提交；不会静默改小后启动。'
    }
    const detailReasons = caught.details?.reasons
    if (detailReasons?.length) {
      return `${caught.message}：${detailReasons.map((item) => reasonLabel(item)).join('；')}`
    }
    return caught.message
  }
  return '操作失败'
}
</script>

<template>
  <div class="page">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert v-if="!capable" type="warning" show-icon message="当前环境不可用负载控制" :description="reasonText" />
    <a-alert
      v-else-if="reasons.length"
      type="info"
      show-icon
      message="启动前还需满足条件"
      :description="reasonText"
    />

    <div class="split">
      <a-card size="small" title="负载参数">
        <a-form layout="vertical" class="form-grid">
          <a-form-item class="span-2" label="这次压什么" tooltip="选这次压 CPU、压内存，还是两边一起。">
            <a-radio-group v-model:value="resourceMode">
              <a-tooltip title="只把 CPU 打忙，内存不动。">
                <span class="tip-wrap">
                  <a-radio value="CPU" :disabled="busy || hasSession">只压 CPU</a-radio>
                </span>
              </a-tooltip>
              <a-tooltip title="只多占内存，不特意把 CPU 打高。">
                <span class="tip-wrap">
                  <a-radio value="MEMORY" :disabled="busy || hasSession">只压内存</a-radio>
                </span>
              </a-tooltip>
              <a-tooltip :title="COMBINED_TIP">
                <span class="tip-wrap">
                  <a-radio value="COMBINED" :disabled="busy || hasSession">CPU 和内存一起</a-radio>
                </span>
              </a-tooltip>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="希望 CPU 忙到（1–90%）" :tooltip="CPU_TARGET_TIP">
            <a-input-number
              v-model:value="targetPct"
              :min="1"
              :max="90"
              :disabled="busy || !usesCpu"
              style="width: 100%"
            />
          </a-form-item>
          <a-form-item label="希望整机内存占用到（1–90%）" :tooltip="SYSTEM_PERCENT_TIP">
            <a-input-number
              v-model:value="memoryTargetPct"
              :min="1"
              :max="90"
              :disabled="busy || !usesMemory"
              style="width: 100%"
            />
          </a-form-item>
          <a-form-item label="多久停" :tooltip="runMode === 'FINITE' ? FINITE_TIP : CONTINUOUS_TIP">
            <a-radio-group v-model:value="runMode">
              <a-tooltip :title="FINITE_TIP">
                <span class="tip-wrap">
                  <a-radio value="FINITE" :disabled="busy || hasSession">到时自动停</a-radio>
                </span>
              </a-tooltip>
              <a-tooltip :title="CONTINUOUS_TIP">
                <span class="tip-wrap">
                  <a-radio value="CONTINUOUS" :disabled="busy || hasSession">一直跑，直到手动停</a-radio>
                </span>
              </a-tooltip>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="runMode === 'FINITE'" label="最多跑多久（秒）" :tooltip="DURATION_TIP">
            <a-input-number
              v-model:value="durationSeconds"
              :min="DURATION_MIN_SECONDS"
              :max="DURATION_MAX_SECONDS"
              :disabled="busy || hasSession"
              style="width: 100%"
            />
          </a-form-item>
          <a-form-item v-if="canEditTarget" class="span-2">
            <a-button :loading="busy" :disabled="applyBlocked" @click="applyTarget">应用目标</a-button>
          </a-form-item>
        </a-form>

        <div class="status-row">
          <a-tooltip :title="startTip">
            <span class="tip-wrap">
              <a-button type="primary" :disabled="!canStart" :loading="busy" @click="start">启动</a-button>
            </span>
          </a-tooltip>
          <a-tooltip title="先看现在能不能开，这次不会真的开始压。">
            <span class="tip-wrap">
              <a-button :disabled="busy" @click="preflight">预检</a-button>
            </span>
          </a-tooltip>
          <a-tooltip :title="canPause ? '先停手，抓着的内存会放开。正在跑的时候才能点。' : '正在跑的时候才能点。'">
            <span class="tip-wrap">
              <a-button :disabled="!canPause" :loading="busy" @click="pause">暂停</a-button>
            </span>
          </a-tooltip>
          <a-tooltip :title="canResume ? '暂停之后接着跑，会再查一遍现在能不能开。' : '暂停之后才能接着跑。'">
            <span class="tip-wrap">
              <a-button :disabled="!canResume" :loading="busy" @click="resume">恢复</a-button>
            </span>
          </a-tooltip>
          <a-tooltip :title="canStop ? '这次结束，抓着的内存会放开。系统账上的内存往往还要过一会儿才降。' : '现在没有可以停的这一次。'">
            <span class="tip-wrap">
              <a-button danger :disabled="!canStop" :loading="busy" @click="stop">停止</a-button>
            </span>
          </a-tooltip>
        </div>
      </a-card>

      <a-card size="small" title="运行状态">
        <a-descriptions bordered size="small" :column="2">
          <a-descriptions-item>
            <template #label><span class="hint-label">控制开关<a-tooltip title="总开关。关上以后不能新开。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ switchLabel(status?.controlSwitch) }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">急停锁存<a-tooltip title="出过严重问题被锁住了。解开之前不能再开。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ status?.emergencyLatched ? `已锁存${status.latchReason ? `（${reasonLabel(status.latchReason)}）` : ''}` : '未锁存' }}
          </a-descriptions-item>
          <a-descriptions-item :span="2">
            <template #label><span class="hint-label">当前会话<a-tooltip title="现在这一次。同时只能有一次。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ active ? `${statusLabel(active.status)} / ${active.executionId}` : '无活动会话' }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">请求目标 CPU<a-tooltip title="你填的 CPU 目标。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ cpu?.targetPct != null ? formatPercent(cpu.targetPct) : '—' }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">实际 CPU<a-tooltip title="机器现在真实有多忙。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ cpu?.actualPct != null ? formatPercent(cpu.actualPct) : '无数据' }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">出力<a-tooltip title="本程序自己出了几分力（duty）。1 是全力，0 是停手。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ cpu?.duty != null ? cpu.duty.toFixed(3) : '—' }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">干活份数<a-tooltip title="一起干活的份数（Worker），一般等于 CPU 核数。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ cpu?.workerCount ?? '—' }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">请求目标内存<a-tooltip title="你填的内存目标。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            <template v-if="memory?.memoryMode === 'EXTRA_BYTES' && memory.memoryTargetBytes != null">
              {{ formatBytes(memory.memoryTargetBytes) }}
            </template>
            <template v-else-if="memory?.memoryMode === 'SYSTEM_PERCENT' && memory.memoryTargetPct != null">
              {{ formatPercent(memory.memoryTargetPct) }}
            </template>
            <template v-else>—</template>
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">当前允许新增<a-tooltip title="按现在的机器情况，还能再多占这么多。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ formatBytes(budget?.additionalBytes) }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">可输入最大值<a-tooltip title="选「多占固定大小」时，最多能填到这里。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ formatBytes(maxAllowedBytes) }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">配置版本<a-tooltip title="这组参数改到第几版，避免旧的把新的盖掉。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ configVersion ?? '—' }}
          </a-descriptions-item>
          <a-descriptions-item :span="2">
            <template #label><span class="hint-label">受限原因<a-tooltip title="没按目标加压的原因，比如内存不够，或数据太旧。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ limitedText }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">负载持有<a-tooltip title="这次负载还抓在手里的内存。一停止就放开，数字回到 0。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ formatBytes(memory?.heldBytes ?? active?.heldBytes ?? 0) }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">JVM 堆使用<a-tooltip title="监控进程自己的堆，不含负载进程。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ formatBytes(memory?.monitorHeapUsedBytes ?? memory?.heapUsedBytes ?? snapshot?.jvm.heapUsedBytes) }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">监控进程驻留<a-tooltip title="操作系统看到监控进程占着的内存。负载不在这个进程里。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ formatBytes(memory?.monitorRssBytes ?? memory?.rssBytes ?? snapshot?.jvm.rssBytes) }}
          </a-descriptions-item>
          <a-descriptions-item>
            <template #label><span class="hint-label">负载进程驻留<a-tooltip title="负载进程占着的物理内存。停止、暂停或急停后进程退出，这里回到 0，内存交还给系统。"><QuestionCircleOutlined class="hint-icon" /></a-tooltip></span></template>
            {{ formatBytes(memory?.loadRssBytes ?? 0) }}
          </a-descriptions-item>
        </a-descriptions>
        <p class="hint status-note">
          停止或暂停后负载进程退出，系统可用内存收回这次占用。监控进程仍保留页面和采集所需的内存。
        </p>
      </a-card>
    </div>
  </div>
</template>

<style scoped>
.status-row {
  margin-top: 4px;
}
.status-note {
  margin-top: 8px;
}
.tip-wrap {
  display: inline-block;
}
.hint-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.hint-icon {
  color: #8c8c8c;
  font-size: 12px;
  cursor: help;
}
</style>
