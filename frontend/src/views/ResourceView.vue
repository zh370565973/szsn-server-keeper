<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ApiError, api, type ResourceStatus } from '../api'
import { formatBytes, formatPercent } from '../format'
import { monitor, refreshResource } from '../monitor'

const MIB = 1024 * 1024
const BLOCK_MIB = 4

const status = ref<ResourceStatus>()
const error = ref('')
const busy = ref(false)
const targetPct = ref(30)
const memoryMode = ref<'EXTRA_BYTES' | 'SYSTEM_PERCENT'>('EXTRA_BYTES')
const memoryTargetMib = ref(256)
const memoryTargetPct = ref(50)
const durationSeconds = ref(300)
const runMode = ref<'FINITE' | 'CONTINUOUS'>('FINITE')
const tab = ref<'cpu' | 'memory' | 'combined'>('cpu')
let timer: number | undefined

const reasons = computed(() => status.value?.controlDisabledReasons ?? [])
const reasonText = computed(() => reasons.value.join('；') || '负载控制不可用')
const cpu = computed(() => status.value?.cpu)
const memory = computed(() => status.value?.memory)
const budget = computed(() => status.value?.budget)
const active = computed(() => status.value?.activeExecution)
const snapshot = computed(() => monitor.snapshot)
const capable = computed(() => !!status.value?.controlCapable)
const switchOn = computed(() => status.value?.controlSwitch === 'ON')
const latched = computed(() => !!status.value?.emergencyLatched)
const hasSession = computed(() => !!active.value)

const maxAllowedBytes = computed(() => {
  const value = budget.value?.maxAllowedBytes
  if (value != null) {
    return value
  }
  const held = budget.value?.heldBytes ?? 0
  const additional = budget.value?.additionalBytes ?? 0
  return held + additional
})
const maxAllowedMib = computed(() => Math.floor(maxAllowedBytes.value / MIB))
const requestedMemoryBytes = computed(() => Math.round(memoryTargetMib.value) * MIB)
const overBudget = computed(
  () => memoryMode.value === 'EXTRA_BYTES' && requestedMemoryBytes.value > maxAllowedBytes.value
)
const alignmentOk = computed(
  () => memoryMode.value !== 'EXTRA_BYTES' || Math.round(memoryTargetMib.value) % BLOCK_MIB === 0
)

const canStart = computed(() =>
  capable.value &&
  switchOn.value &&
  !latched.value &&
  !hasSession.value &&
  !busy.value &&
  (tab.value === 'cpu' || (alignmentOk.value && !overBudget.value))
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
  return items.length ? Array.from(new Set(items)).join('；') : '无'
})

const configVersion = computed(() => {
  if (tab.value === 'memory') {
    return memory.value?.configVersion ?? active.value?.configVersion ?? null
  }
  return cpu.value?.configVersion ?? memory.value?.configVersion ?? active.value?.configVersion ?? null
})

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
    }
  }
)

async function reload() {
  try {
    status.value = await api<ResourceStatus>('/resource/status')
    monitor.resource = status.value
    error.value = ''
    if (hasSession.value) {
      if (status.value.cpu?.targetPct != null) {
        targetPct.value = status.value.cpu.targetPct
      }
      if (status.value.memory?.memoryMode) {
        memoryMode.value = status.value.memory.memoryMode as 'EXTRA_BYTES' | 'SYSTEM_PERCENT'
      }
      if (status.value.memory?.memoryTargetBytes != null) {
        memoryTargetMib.value = Math.round(status.value.memory.memoryTargetBytes / MIB)
      }
      if (status.value.memory?.memoryTargetPct != null) {
        memoryTargetPct.value = status.value.memory.memoryTargetPct
      }
    }
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法读取控制状态'
  }
}

function newKey() {
  return crypto.randomUUID()
}

function buildBody(): Record<string, unknown> {
  const body: Record<string, unknown> = {
    runMode: runMode.value
  }
  if (runMode.value === 'FINITE') {
    body.durationSeconds = durationSeconds.value
  }
  if (tab.value === 'cpu') {
    body.resourceMode = 'CPU'
    body.cpuTargetPct = targetPct.value
  } else if (tab.value === 'memory') {
    body.resourceMode = 'MEMORY'
    body.memoryMode = memoryMode.value
    if (memoryMode.value === 'EXTRA_BYTES') {
      body.memoryTargetBytes = requestedMemoryBytes.value
    } else {
      body.memoryTargetPct = memoryTargetPct.value
    }
  } else {
    body.resourceMode = 'COMBINED'
    body.cpuTargetPct = targetPct.value
    body.memoryMode = memoryMode.value
    if (memoryMode.value === 'EXTRA_BYTES') {
      body.memoryTargetBytes = requestedMemoryBytes.value
    } else {
      body.memoryTargetPct = memoryTargetPct.value
    }
  }
  return body
}

async function start() {
  if (overBudget.value) {
    error.value = `固定容量超过当前预算。可输入最大值为 ${maxAllowedMib.value} MiB，请改小后再提交；不会静默改小后启动。`
    return
  }
  if (!alignmentOk.value) {
    error.value = `固定容量必须是 ${BLOCK_MIB} MiB 的整数倍`
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
    message.success(`已受理启动，状态 ${accepted.status}。实际调节以状态为准，不假定已达目标。`)
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
    message.success(`已受理暂停，当前 ${accepted.status}`)
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
    message.success(`已受理恢复，当前 ${accepted.status}`)
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
    message.success(`已受理停止，当前 ${accepted.status}`)
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
  if ((tab.value === 'memory' || tab.value === 'combined') && overBudget.value) {
    error.value = `固定容量超过当前预算。可输入最大值为 ${maxAllowedMib.value} MiB，禁止提交；不会静默改小。`
    return
  }
  busy.value = true
  try {
    const body: Record<string, unknown> = {
      expectedVersion: configVersion.value
    }
    if (tab.value === 'cpu' || tab.value === 'combined') {
      body.cpuTargetPct = targetPct.value
    }
    if (tab.value === 'memory' || tab.value === 'combined') {
      if (memoryMode.value === 'EXTRA_BYTES') {
        body.memoryTargetBytes = requestedMemoryBytes.value
      } else {
        body.memoryTargetPct = memoryTargetPct.value
      }
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
      const maxBytes = result.maxAllowedBytes ?? result.budget?.maxAllowedBytes
      const maxHint =
        maxBytes != null && overBudget.value
          ? `可输入最大值 ${Math.floor(maxBytes / MIB)} MiB。`
          : ''
      message.warning(`${(result.reasons ?? []).join('；') || '预检未通过'}。${maxHint}`.trim())
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
      const maxBytes = Number(caught.details?.maxAllowedBytes)
      const maxMib = Number.isFinite(maxBytes) ? Math.floor(maxBytes / MIB) : maxAllowedMib.value
      return `固定内存目标超过当前预算。可输入最大值为 ${maxMib} MiB，请改小后再提交；不会静默改小后启动。`
    }
    const detailReasons = caught.details?.reasons
    if (detailReasons?.length) {
      return `${caught.message}：${detailReasons.join('；')}`
    }
    return caught.message
  }
  return caught instanceof Error ? caught.message : '操作失败'
}
</script>

<template>
  <div class="page">
    <a-alert v-if="error" type="error" show-icon :message="error" style="margin-bottom: 12px" />
    <a-alert
      v-if="!capable"
      type="warning"
      show-icon
      message="当前环境不可用负载控制"
      :description="reasonText"
      style="margin-bottom: 12px"
    />
    <a-alert
      v-else-if="reasons.length"
      type="info"
      show-icon
      message="启动前还需满足条件"
      :description="reasonText"
      style="margin-bottom: 12px"
    />

    <a-tabs v-model:activeKey="tab">
      <a-tab-pane key="cpu" tab="CPU">
        <a-form layout="vertical">
          <a-form-item label="目标 CPU（1–60%）">
            <a-input-number v-model:value="targetPct" :min="1" :max="60" :disabled="busy" style="width: 180px" />
            <a-button v-if="canEditTarget && tab === 'cpu'" style="margin-left: 8px" :loading="busy" @click="applyTarget">
              应用目标
            </a-button>
          </a-form-item>
          <a-form-item label="运行模式">
            <a-radio-group v-model:value="runMode" :disabled="busy || hasSession">
              <a-radio value="FINITE">限时</a-radio>
              <a-radio value="CONTINUOUS">持续</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="runMode === 'FINITE'" label="时长（秒）">
            <a-input-number
              v-model:value="durationSeconds"
              :min="1"
              :max="86400"
              :disabled="busy || hasSession"
              style="width: 180px"
            />
          </a-form-item>
        </a-form>
      </a-tab-pane>

      <a-tab-pane key="memory" tab="内存">
        <a-form layout="vertical">
          <a-form-item label="模式">
            <a-radio-group v-model:value="memoryMode" :disabled="busy || hasSession">
              <a-radio value="EXTRA_BYTES">固定增加</a-radio>
              <a-radio value="SYSTEM_PERCENT">系统占用比例</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="memoryMode === 'EXTRA_BYTES'" label="目标持有（MiB，4 的倍数）">
            <a-input-number
              v-model:value="memoryTargetMib"
              :min="4"
              :step="4"
              :disabled="busy"
              style="width: 180px"
            />
            <a-button
              v-if="canEditTarget && tab === 'memory'"
              style="margin-left: 8px"
              :loading="busy"
              :disabled="overBudget || !alignmentOk"
              @click="applyTarget"
            >
              应用目标
            </a-button>
          </a-form-item>
          <a-form-item v-else label="系统占用目标（1–70%）">
            <a-input-number v-model:value="memoryTargetPct" :min="1" :max="70" :disabled="busy" style="width: 180px" />
            <a-button v-if="canEditTarget && tab === 'memory'" style="margin-left: 8px" :loading="busy" @click="applyTarget">
              应用目标
            </a-button>
          </a-form-item>
          <a-alert
            v-if="memoryMode === 'EXTRA_BYTES' && overBudget"
            type="error"
            show-icon
            style="margin-bottom: 12px"
            message="超过当前预算"
            :description="`可输入最大值为 ${maxAllowedMib} MiB。请改小后再提交；不会静默改小目标后启动。`"
          />
          <a-form-item label="运行模式">
            <a-radio-group v-model:value="runMode" :disabled="busy || hasSession">
              <a-radio value="FINITE">限时</a-radio>
              <a-radio value="CONTINUOUS">持续</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="runMode === 'FINITE'" label="时长（秒）">
            <a-input-number
              v-model:value="durationSeconds"
              :min="1"
              :max="86400"
              :disabled="busy || hasSession"
              style="width: 180px"
            />
          </a-form-item>
          <p>
            暂停或停止会在 2 秒内清除负载引用并使负载持有归零。这不代表进程驻留内存已下降，系统回收由 JVM 和操作系统决定。
          </p>
        </a-form>
      </a-tab-pane>

      <a-tab-pane key="combined" tab="联合">
        <a-form layout="vertical">
          <a-form-item label="目标 CPU（1–60%）">
            <a-input-number v-model:value="targetPct" :min="1" :max="60" :disabled="busy" style="width: 180px" />
          </a-form-item>
          <a-form-item label="内存模式">
            <a-radio-group v-model:value="memoryMode" :disabled="busy || hasSession">
              <a-radio value="EXTRA_BYTES">固定增加</a-radio>
              <a-radio value="SYSTEM_PERCENT">系统占用比例</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="memoryMode === 'EXTRA_BYTES'" label="目标持有（MiB，4 的倍数）">
            <a-input-number
              v-model:value="memoryTargetMib"
              :min="4"
              :step="4"
              :disabled="busy"
              style="width: 180px"
            />
          </a-form-item>
          <a-form-item v-else label="系统占用目标（1–70%）">
            <a-input-number v-model:value="memoryTargetPct" :min="1" :max="70" :disabled="busy" style="width: 180px" />
          </a-form-item>
          <a-alert
            v-if="memoryMode === 'EXTRA_BYTES' && overBudget"
            type="error"
            show-icon
            style="margin-bottom: 12px"
            message="超过当前预算"
            :description="`可输入最大值为 ${maxAllowedMib} MiB。请改小后再提交；不会静默改小目标后启动。`"
          />
          <a-button v-if="canEditTarget" style="margin-bottom: 12px" :loading="busy" :disabled="overBudget" @click="applyTarget">
            应用目标
          </a-button>
          <a-form-item label="运行模式">
            <a-radio-group v-model:value="runMode" :disabled="busy || hasSession">
              <a-radio value="FINITE">限时</a-radio>
              <a-radio value="CONTINUOUS">持续</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="runMode === 'FINITE'" label="时长（秒）">
            <a-input-number
              v-model:value="durationSeconds"
              :min="1"
              :max="86400"
              :disabled="busy || hasSession"
              style="width: 180px"
            />
          </a-form-item>
          <p>联合会话任一引擎启动失败都会回收两侧，不会静默只跑 CPU 或只跑内存。</p>
        </a-form>
      </a-tab-pane>
    </a-tabs>

    <div class="status-row">
      <a-tooltip :title="canStart ? '正式启动会重新预检并争用槽位' : overBudget ? `超过预算，最大 ${maxAllowedMib} MiB` : reasonText">
        <a-button type="primary" :disabled="!canStart" :loading="busy" @click="start">启动</a-button>
      </a-tooltip>
      <a-button :disabled="busy" @click="preflight">预检</a-button>
      <a-tooltip :title="canPause ? '' : '仅 RUNNING 可暂停'">
        <a-button :disabled="!canPause" :loading="busy" @click="pause">暂停</a-button>
      </a-tooltip>
      <a-tooltip :title="canResume ? '' : '仅 PAUSED 可恢复'">
        <a-button :disabled="!canResume" :loading="busy" @click="resume">恢复</a-button>
      </a-tooltip>
      <a-tooltip :title="canStop ? '' : '没有可停止的会话'">
        <a-button danger :disabled="!canStop" :loading="busy" @click="stop">停止</a-button>
      </a-tooltip>
    </div>

    <a-descriptions bordered size="small" :column="1" style="margin-top: 16px">
      <a-descriptions-item label="控制开关">{{ status?.controlSwitch ?? 'OFF' }}</a-descriptions-item>
      <a-descriptions-item label="急停锁存">
        {{ status?.emergencyLatched ? `已锁存${status.latchReason ? `（${status.latchReason}）` : ''}` : '未锁存' }}
      </a-descriptions-item>
      <a-descriptions-item label="当前会话">
        {{ active ? `${active.status} / ${active.executionId}` : '无活动会话' }}
      </a-descriptions-item>
      <a-descriptions-item label="请求目标 CPU">
        {{ cpu?.targetPct != null ? formatPercent(cpu.targetPct) : '—' }}
      </a-descriptions-item>
      <a-descriptions-item label="实际 CPU">
        {{ cpu?.actualPct != null ? formatPercent(cpu.actualPct) : '无数据' }}
      </a-descriptions-item>
      <a-descriptions-item label="duty">
        {{ cpu?.duty != null ? cpu.duty.toFixed(3) : '—' }}
      </a-descriptions-item>
      <a-descriptions-item label="Worker 数">
        {{ cpu?.workerCount ?? '—' }}
      </a-descriptions-item>
      <a-descriptions-item label="请求目标内存">
        <template v-if="memory?.memoryMode === 'EXTRA_BYTES' && memory.memoryTargetBytes != null">
          {{ formatBytes(memory.memoryTargetBytes) }}
        </template>
        <template v-else-if="memory?.memoryMode === 'SYSTEM_PERCENT' && memory.memoryTargetPct != null">
          {{ formatPercent(memory.memoryTargetPct) }}
        </template>
        <template v-else>—</template>
      </a-descriptions-item>
      <a-descriptions-item label="当前允许新增">
        {{ formatBytes(budget?.additionalBytes) }}
      </a-descriptions-item>
      <a-descriptions-item label="可输入最大值">
        {{ formatBytes(maxAllowedBytes) }}
      </a-descriptions-item>
      <a-descriptions-item label="受限原因">{{ limitedText }}</a-descriptions-item>
      <a-descriptions-item label="配置版本">{{ configVersion ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="负载持有">
        {{ formatBytes(memory?.heldBytes ?? active?.heldBytes ?? 0) }}
      </a-descriptions-item>
      <a-descriptions-item label="JVM 堆使用">
        {{ formatBytes(memory?.heapUsedBytes ?? snapshot?.jvm.heapUsedBytes) }}
      </a-descriptions-item>
      <a-descriptions-item label="进程驻留内存">
        {{ formatBytes(memory?.rssBytes ?? snapshot?.jvm.rssBytes) }}
      </a-descriptions-item>
    </a-descriptions>
    <a-alert
      type="info"
      show-icon
      style="margin-top: 12px"
      message="停止后内存说明"
      description="负载引用已释放，系统内存回收由 JVM/OS 决定。负载持有、JVM 堆使用与进程驻留内存口径不同，请分开解读。"
    />
  </div>
</template>

<style scoped>
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
</style>
