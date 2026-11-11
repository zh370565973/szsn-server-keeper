<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ApiError,
  api,
  explainError,
  type AcceptedExecution,
  type PageResult,
  type TaskTemplate
} from '../api'
import { formatBytes, formatDuration, formatTime } from '../format'
import { memoryModeLabel, resourceModeLabel, runModeLabel, scheduleTypeLabel, statusLabel } from '../labels'
import { monitor } from '../monitor'

const MIB = 1024 * 1024
const loading = ref(false)
const saving = ref(false)
const runningId = ref('')
const error = ref('')
const modalOpen = ref(false)
const items = ref<TaskTemplate[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const keyword = ref('')
const appliedQuery = ref('')

const form = reactive({
  id: '',
  version: 1,
  name: '',
  description: '',
  resourceMode: 'CPU' as 'CPU' | 'MEMORY' | 'COMBINED',
  cpuTargetPct: 30 as number | null,
  memoryMode: 'EXTRA_BYTES' as 'EXTRA_BYTES' | 'SYSTEM_PERCENT',
  memoryTargetMib: 256 as number | null,
  memoryTargetPct: 40 as number | null,
  runMode: 'FINITE' as 'FINITE' | 'CONTINUOUS',
  durationSeconds: 300 as number | null
})

const columns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '模式', key: 'mode', width: 220 },
  { title: '运行', key: 'run', width: 140 },
  { title: '版本', dataIndex: 'version', width: 70 },
  { title: '更新时间', key: 'updated', width: 160 },
  { title: '操作', key: 'actions', width: 220 }
]

const pagination = computed(() => ({
  current: page.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (value: number) => `共 ${value} 条`
}))

const runBlockReason = computed(() => {
  const resource = monitor.resource
  if (!resource) {
    return '正在读取控制状态'
  }
  const reasons = [...resource.controlDisabledReasons]
  if (!resource.controlCapable && reasons.length === 0) {
    reasons.push('当前环境不能启动负载')
  }
  if (resource.emergencyLatched) {
    reasons.push('急停已锁存')
  }
  if (resource.controlSwitch !== 'ON') {
    reasons.push('控制开关已关闭')
  }
  if (resource.activeExecution) {
    reasons.push('已有活动会话')
  }
  return Array.from(new Set(reasons)).join('；')
})
const canRun = computed(() => runBlockReason.value === '')

const formBlockReason = computed(() => {
  const name = form.name.trim()
  const nameLength = Array.from(name).length
  if (nameLength < 1 || nameLength > 64) {
    return '名称需要 1 到 64 个字符'
  }
  if (Array.from(form.description).length > 500) {
    return '说明不能超过 500 个字符'
  }
  if (form.resourceMode !== 'MEMORY') {
    const cpu = form.cpuTargetPct
    if (cpu == null || cpu < 1 || cpu > 60) {
      return 'CPU 目标必须在 1 到 60 之间'
    }
  }
  if (form.resourceMode !== 'CPU') {
    if (form.memoryMode === 'EXTRA_BYTES') {
      const mib = form.memoryTargetMib
      if (mib == null || !Number.isInteger(mib) || mib < 4 || mib % 4 !== 0) {
        return '额外容量必须是 4MiB 的整数倍'
      }
    } else {
      const pct = form.memoryTargetPct
      if (pct == null || pct < 1 || pct > 70) {
        return '系统占用目标必须在 1 到 70 之间'
      }
    }
  }
  if (form.runMode === 'FINITE') {
    const duration = form.durationSeconds
    if (duration == null || !Number.isInteger(duration) || duration < 10 || duration > 7 * 24 * 60 * 60) {
      return '限时时长必须在 10 秒到 7 天之间'
    }
  }
  return ''
})

function targetText(task: TaskTemplate) {
  const parts: string[] = []
  if (task.cpuTargetPct != null) {
    parts.push(`CPU ${task.cpuTargetPct}%`)
  }
  if (task.memoryMode === 'EXTRA_BYTES' && task.memoryTargetBytes != null) {
    parts.push(`${memoryModeLabel(task.memoryMode)} ${formatBytes(task.memoryTargetBytes)}`)
  }
  if (task.memoryMode === 'SYSTEM_PERCENT' && task.memoryTargetPct != null) {
    parts.push(`${memoryModeLabel(task.memoryMode)} ${task.memoryTargetPct}%`)
  }
  return parts.join('，') || '无目标'
}

function resetForm() {
  form.id = ''
  form.version = 1
  form.name = ''
  form.description = ''
  form.resourceMode = 'CPU'
  form.cpuTargetPct = 30
  form.memoryMode = 'EXTRA_BYTES'
  form.memoryTargetMib = 256
  form.memoryTargetPct = 40
  form.runMode = 'FINITE'
  form.durationSeconds = 300
}

function openCreate() {
  resetForm()
  modalOpen.value = true
}

function openEdit(task: TaskTemplate) {
  resetForm()
  form.id = task.id
  form.version = task.version
  form.name = task.name
  form.description = task.description ?? ''
  form.resourceMode = task.resourceMode === 'MEMORY' || task.resourceMode === 'COMBINED' ? task.resourceMode : 'CPU'
  form.cpuTargetPct = task.cpuTargetPct ?? 30
  form.memoryMode = task.memoryMode === 'SYSTEM_PERCENT' ? 'SYSTEM_PERCENT' : 'EXTRA_BYTES'
  form.memoryTargetMib = task.memoryTargetBytes != null ? task.memoryTargetBytes / MIB : 256
  form.memoryTargetPct = task.memoryTargetPct ?? 40
  form.runMode = task.runMode === 'CONTINUOUS' ? 'CONTINUOUS' : 'FINITE'
  form.durationSeconds = task.durationSeconds ?? 300
  modalOpen.value = true
}

function payload() {
  const body: Record<string, unknown> = {
    name: form.name.trim(),
    resourceMode: form.resourceMode,
    runMode: form.runMode
  }
  const description = form.description.trim()
  if (description) {
    body.description = description
  }
  if (form.resourceMode !== 'MEMORY') {
    body.cpuTargetPct = form.cpuTargetPct
  }
  if (form.resourceMode !== 'CPU') {
    body.memoryMode = form.memoryMode
    if (form.memoryMode === 'EXTRA_BYTES') {
      body.memoryTargetBytes = (form.memoryTargetMib ?? 0) * MIB
    } else {
      body.memoryTargetPct = form.memoryTargetPct
    }
  }
  if (form.runMode === 'FINITE') {
    body.durationSeconds = form.durationSeconds
  }
  if (form.id) {
    body.expectedVersion = form.version
  }
  return body
}

async function load() {
  loading.value = true
  error.value = ''
  const params = new URLSearchParams()
  params.set('page', String(page.value))
  params.set('pageSize', String(pageSize.value))
  if (appliedQuery.value) {
    params.set('q', appliedQuery.value)
  }
  try {
    const result = await api<PageResult<TaskTemplate>>('/tasks?' + params.toString())
    items.value = result.items
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
  } catch (caught) {
    error.value = explainError(caught, '模板加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  appliedQuery.value = keyword.value.trim()
  page.value = 1
  void load()
}

function onTableChange(pager: { current?: number; pageSize?: number }) {
  const nextSize = pager.pageSize ?? pageSize.value
  page.value = nextSize === pageSize.value ? pager.current ?? 1 : 1
  pageSize.value = nextSize
  void load()
}

async function save() {
  if (formBlockReason.value || saving.value) {
    return
  }
  saving.value = true
  error.value = ''
  try {
    const path = form.id ? `/tasks/${form.id}` : '/tasks'
    await api<TaskTemplate>(path, { method: form.id ? 'PUT' : 'POST', body: JSON.stringify(payload()) })
    message.success(form.id ? '模板已更新，已启动的实例保持原快照' : '模板已创建')
    modalOpen.value = false
    await load()
  } catch (caught) {
    error.value = failureText(caught, '保存失败')
    message.error(error.value)
  } finally {
    saving.value = false
  }
}

function remove(task: TaskTemplate) {
  Modal.confirm({
    title: `删除模板「${task.name}」？`,
    content: '没有被计划引用时会软删除。已经产生的执行快照仍保留。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await api(`/tasks/${task.id}`, { method: 'DELETE' })
        message.success('已删除')
        await load()
      } catch (caught) {
        error.value = failureText(caught, '删除失败')
        message.error(error.value)
      }
    }
  })
}

function confirmRun(task: TaskTemplate) {
  if (!canRun.value || runningId.value) {
    return
  }
  const continuous = task.runMode === 'CONTINUOUS'
  Modal.confirm({
    title: continuous ? `启动持续任务「${task.name}」？` : `运行模板「${task.name}」？`,
    content: continuous
      ? `目标：${targetText(task)}。持续任务不会自动停止，需要在资源控制页停止，或使用页面上的紧急停止。受理成功不表示引擎已经完成。`
      : `目标：${targetText(task)}，时长 ${formatDuration(task.durationSeconds)}。受理成功不表示引擎已经完成。`,
    okText: '受理运行',
    cancelText: '取消',
    async onOk() {
      await run(task)
    }
  })
}

async function run(task: TaskTemplate) {
  runningId.value = task.id
  try {
    const accepted = await api<AcceptedExecution>(`/tasks/${task.id}/run`, {
      method: 'POST',
      headers: { 'Idempotency-Key': newIdempotencyKey() }
    })
    const state = statusLabel(accepted.status)
    if (accepted.engineCompleted) {
      message.success(`执行已结束，状态 ${state}`)
    } else if (accepted.replay) {
      message.success(`这是同一次执行，状态 ${state}`)
    } else {
      message.success(`已受理，当前状态 ${state}。请到执行记录确认是否已经跑完`)
    }
  } catch (caught) {
    message.error(explainError(caught, '启动失败'))
    throw caught
  } finally {
    runningId.value = ''
  }
}

function failureText(caught: unknown, fallback: string) {
  const base = explainError(caught, fallback)
  if (caught instanceof ApiError && caught.details?.schedules?.length) {
    const listed = caught.details.schedules
      .map((item) => `${scheduleTypeLabel(item.type)} ${item.id ?? ''}`.trim())
      .join('；')
    return `${base}：${listed}`
  }
  return base
}

function newIdempotencyKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

onMounted(load)
</script>

<template>
  <div class="page">
    <a-card size="small">
      <div class="status-row">
        <span>修改模板不会改变已经启动的实例。被计划引用时不能删除。</span>
        <a-input-search
          v-model:value="keyword"
          placeholder="按名称搜索"
          style="width: 240px"
          allow-clear
          @search="search"
        />
        <a-button :loading="loading" @click="load">刷新</a-button>
        <a-button type="primary" @click="openCreate">创建模板</a-button>
      </div>
    </a-card>
    <a-alert v-if="runBlockReason" type="warning" show-icon :message="`立即运行不可用：${runBlockReason}`" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table
      size="small"
      :loading="loading"
      :columns="columns"
      :data-source="items"
      :pagination="pagination"
      :scroll="{ x: 980 }"
      row-key="id"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'mode'">
          {{ resourceModeLabel(record.resourceMode) }} / {{ targetText(record) }}
        </template>
        <template v-else-if="column.key === 'run'">
          {{ runModeLabel(record.runMode) }}
          <span v-if="record.runMode === 'FINITE'"> {{ formatDuration(record.durationSeconds) }}</span>
        </template>
        <template v-else-if="column.key === 'updated'">{{ formatTime(record.updatedAt) }}</template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
          <a-button type="link" size="small" danger @click="remove(record)">删除</a-button>
          <a-button
            type="link"
            size="small"
            :disabled="!canRun || runningId === record.id"
            :title="canRun ? '按当前模板受理执行' : runBlockReason"
            :loading="runningId === record.id"
            @click="confirmRun(record)"
          >
            立即运行
          </a-button>
        </template>
      </template>
      <template #emptyText>没有模板。可以创建一条，搜索无结果时也会显示这里。</template>
    </a-table>

    <a-modal
      :open="modalOpen"
      :title="form.id ? '编辑模板' : '创建模板'"
      :confirm-loading="saving"
      :ok-button-props="{ disabled: !!formBlockReason }"
      ok-text="保存"
      cancel-text="取消"
      width="640px"
      @ok="save"
      @cancel="modalOpen = false"
    >
      <a-alert v-if="formBlockReason" type="warning" show-icon :message="formBlockReason" style="margin-bottom: 12px" />
      <a-form layout="vertical">
        <a-form-item label="名称">
          <a-input v-model:value="form.name" :maxlength="64" placeholder="1 到 64 个字符" />
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea v-model:value="form.description" :maxlength="500" :rows="2" show-count />
        </a-form-item>
        <a-form-item label="资源">
          <a-radio-group v-model:value="form.resourceMode">
            <a-radio-button value="CPU">CPU</a-radio-button>
            <a-radio-button value="MEMORY">内存</a-radio-button>
            <a-radio-button value="COMBINED">联合</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="form.resourceMode !== 'MEMORY'" label="CPU 目标（1–60）">
          <a-input-number v-model:value="form.cpuTargetPct" :min="1" :max="60" :step="1" style="width: 160px" />
        </a-form-item>
        <template v-if="form.resourceMode !== 'CPU'">
          <a-form-item label="内存方式">
            <a-radio-group v-model:value="form.memoryMode">
              <a-radio-button value="EXTRA_BYTES">额外容量</a-radio-button>
              <a-radio-button value="SYSTEM_PERCENT">系统占用</a-radio-button>
            </a-radio-group>
          </a-form-item>
          <a-form-item v-if="form.memoryMode === 'EXTRA_BYTES'" label="额外容量（MiB，4 的倍数）">
            <a-input-number v-model:value="form.memoryTargetMib" :min="4" :step="4" :precision="0" style="width: 160px" />
          </a-form-item>
          <a-form-item v-else label="系统占用（1–70）">
            <a-input-number v-model:value="form.memoryTargetPct" :min="1" :max="70" :step="1" style="width: 160px" />
          </a-form-item>
        </template>
        <a-form-item label="时长">
          <a-radio-group v-model:value="form.runMode">
            <a-radio-button value="FINITE">限时</a-radio-button>
            <a-radio-button value="CONTINUOUS">持续</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="form.runMode === 'FINITE'" label="时长（秒，10 秒到 7 天）">
          <a-input-number v-model:value="form.durationSeconds" :min="10" :precision="0" style="width: 180px" />
        </a-form-item>
        <p v-else>持续任务没有自动停止时间，只能手动停止或紧急停止。</p>
      </a-form>
    </a-modal>
  </div>
</template>
