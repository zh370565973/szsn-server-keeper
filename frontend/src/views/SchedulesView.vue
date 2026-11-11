<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { api, explainError, type PageResult, type ScheduleItem, type TaskTemplate } from '../api'
import { epochFromLocal, toLocalInput } from '../format'
import { nextFireText, scheduleTypeLabel, skipText, weekdayText } from '../labels'

const DAY = 24 * 60 * 60
const LOCAL_TIME = /^(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d$/
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const templateError = ref('')
const modalOpen = ref(false)
const now = ref(Date.now())
const items = ref<ScheduleItem[]>([])
const templates = ref<TaskTemplate[]>([])
const templateTotal = ref(0)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const form = reactive({
  id: '',
  version: 1,
  taskId: '',
  type: 'DAILY' as 'ONCE' | 'DAILY' | 'WEEKLY' | 'FIXED_INTERVAL',
  enabled: true,
  startAt: '',
  timezone: 'Asia/Shanghai',
  localTime: '09:00:00',
  weekdays: [1] as number[],
  anchorAt: '',
  intervalSeconds: 3600 as number | null
})

const weekdayOptions = [
  { label: '周一', value: 1 },
  { label: '周二', value: 2 },
  { label: '周三', value: 3 },
  { label: '周四', value: 4 },
  { label: '周五', value: 5 },
  { label: '周六', value: 6 },
  { label: '周日', value: 7 }
]

const columns = [
  { title: '模板', key: 'task' },
  { title: '类型', key: 'type', width: 100 },
  { title: '规则', key: 'rule' },
  { title: '下次触发', key: 'next', width: 220 },
  { title: '最近跳过', key: 'skip', width: 280 },
  { title: '启停', key: 'enabled', width: 90 },
  { title: '操作', key: 'actions', width: 140 }
]

const pagination = computed(() => ({
  current: page.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (value: number) => `共 ${value} 条`
}))

const selectedTemplate = computed(() => templates.value.find((item) => item.id === form.taskId))
const intervalForCheck = computed(() =>
  form.type === 'FIXED_INTERVAL' && form.intervalSeconds != null && Number.isInteger(form.intervalSeconds)
    ? form.intervalSeconds
    : null
)

const formBlockReason = computed(() => {
  if (!form.taskId) {
    return '请选择任务模板'
  }
  const selected = selectedTemplate.value
  if (!selected && !form.id) {
    return '请选择任务模板'
  }
  if (selected) {
    const reason = bindReason(selected, intervalForCheck.value)
    if (reason) {
      return reason
    }
  }
  if (form.type === 'ONCE') {
    const start = epochFromLocal(form.startAt)
    if (start == null) {
      return '请填写开始时间'
    }
    if (start <= now.value) {
      return '开始时间必须晚于当前时间'
    }
  }
  if (form.type === 'DAILY' || form.type === 'WEEKLY') {
    if (!form.timezone.trim()) {
      return '请填写时区'
    }
    if (!LOCAL_TIME.test(form.localTime.trim())) {
      return '本地时间必须是 HH:mm:ss'
    }
  }
  if (form.type === 'WEEKLY' && uniqueWeekdays(form.weekdays).length === 0) {
    return '请至少选择一个星期'
  }
  if (form.type === 'FIXED_INTERVAL') {
    if (epochFromLocal(form.anchorAt) == null) {
      return '请填写间隔锚点'
    }
    if (form.intervalSeconds == null || !Number.isInteger(form.intervalSeconds) || form.intervalSeconds < 60) {
      return '固定间隔至少 60 秒'
    }
  }
  return ''
})

function bindReason(task: TaskTemplate, intervalSeconds: number | null) {
  if (task.runMode !== 'FINITE' || task.durationSeconds == null) {
    return '计划只能绑定有限时长模板'
  }
  if (task.durationSeconds > DAY) {
    return '执行时长不能超过 24 小时'
  }
  if (intervalSeconds != null && task.durationSeconds > intervalSeconds) {
    return '执行时长不能超过固定间隔'
  }
  return ''
}

function uniqueWeekdays(days: number[]) {
  return Array.from(new Set(days.filter((day) => day >= 1 && day <= 7))).sort((left, right) => left - right)
}

function taskName(id: string) {
  return templates.value.find((item) => item.id === id)?.name ?? id
}

function ruleText(item: ScheduleItem) {
  if (item.type === 'ONCE') {
    return `开始于 ${toLocalInput(item.startAt).replace('T', ' ')}`
  }
  if (item.type === 'DAILY') {
    return `每天 ${item.localTime}（${item.timezone}）`
  }
  if (item.type === 'WEEKLY') {
    return `每周 ${weekdayText(item.weekdays)} ${item.localTime}（${item.timezone}）`
  }
  if (item.type === 'FIXED_INTERVAL') {
    return `每 ${item.intervalSeconds} 秒，锚点 ${toLocalInput(item.anchorAt).replace('T', ' ')}`
  }
  return scheduleTypeLabel(item.type)
}

function resetForm() {
  form.id = ''
  form.version = 1
  form.taskId = ''
  form.type = 'DAILY'
  form.enabled = true
  form.startAt = ''
  form.timezone = 'Asia/Shanghai'
  form.localTime = '09:00:00'
  form.weekdays = [1]
  form.anchorAt = ''
  form.intervalSeconds = 3600
}

function openCreate() {
  now.value = Date.now()
  resetForm()
  modalOpen.value = true
}

function openEdit(item: ScheduleItem) {
  now.value = Date.now()
  resetForm()
  form.id = item.id
  form.version = item.version
  form.taskId = item.taskId
  form.type = (['ONCE', 'DAILY', 'WEEKLY', 'FIXED_INTERVAL'].includes(item.type) ? item.type : 'DAILY') as typeof form.type
  form.enabled = item.enabled
  form.startAt = toLocalInput(item.startAt)
  form.timezone = item.timezone || 'Asia/Shanghai'
  form.localTime = item.localTime || '09:00:00'
  form.weekdays = item.weekdays?.length ? [...item.weekdays] : [1]
  form.anchorAt = toLocalInput(item.anchorAt)
  form.intervalSeconds = item.intervalSeconds ?? 3600
  modalOpen.value = true
}

function payload() {
  const body: Record<string, unknown> = {
    taskId: form.taskId,
    type: form.type,
    enabled: form.enabled
  }
  if (form.type === 'ONCE') {
    body.startAt = epochFromLocal(form.startAt)
  } else if (form.type === 'DAILY' || form.type === 'WEEKLY') {
    body.timezone = form.timezone.trim()
    body.localTime = form.localTime.trim()
    if (form.type === 'WEEKLY') {
      body.weekdays = uniqueWeekdays(form.weekdays)
    }
  } else {
    body.anchorAt = epochFromLocal(form.anchorAt)
    body.intervalSeconds = form.intervalSeconds
  }
  if (form.id) {
    body.expectedVersion = form.version
  }
  return body
}

async function loadTemplates() {
  templateError.value = ''
  try {
    const result = await api<PageResult<TaskTemplate>>('/tasks?page=1&pageSize=100')
    templates.value = result.items
    templateTotal.value = result.total
  } catch (caught) {
    templateError.value = explainError(caught, '模板加载失败')
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api<PageResult<ScheduleItem>>(`/schedules?page=${page.value}&pageSize=${pageSize.value}`)
    items.value = result.items
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
  } catch (caught) {
    error.value = explainError(caught, '计划加载失败')
  } finally {
    loading.value = false
  }
}

function onTableChange(pager: { current?: number; pageSize?: number }) {
  const nextSize = pager.pageSize ?? pageSize.value
  page.value = nextSize === pageSize.value ? pager.current ?? 1 : 1
  pageSize.value = nextSize
  void load()
}

async function save() {
  now.value = Date.now()
  if (formBlockReason.value || saving.value) {
    return
  }
  saving.value = true
  try {
    const path = form.id ? `/schedules/${form.id}` : '/schedules'
    const saved = await api<ScheduleItem>(path, {
      method: form.id ? 'PUT' : 'POST',
      body: JSON.stringify(payload())
    })
    message.success(`已保存。下次触发：${nextFireText(saved.nextFireAt, saved.enabled)}`)
    modalOpen.value = false
    await load()
  } catch (caught) {
    error.value = explainError(caught, '保存失败')
    message.error(error.value)
  } finally {
    saving.value = false
  }
}

function remove(item: ScheduleItem) {
  Modal.confirm({
    title: '删除这个计划？',
    content: '删除后不再触发。已经启动的实例会继续，直到它自己结束或被停止。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await api(`/schedules/${item.id}`, { method: 'DELETE' })
        message.success('计划已删除')
        await load()
      } catch (caught) {
        error.value = explainError(caught, '删除失败')
        message.error(error.value)
      }
    }
  })
}

async function toggle(item: ScheduleItem, enabled: boolean) {
  try {
    const saved = await api<ScheduleItem>(`/schedules/${item.id}/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled, expectedVersion: item.version })
    })
    message.success(enabled
      ? `已启用。下次触发：${nextFireText(saved.nextFireAt, true)}`
      : '已关闭。已启动的实例不会因此停止')
  } catch (caught) {
    error.value = explainError(caught, '启停失败')
    message.error(error.value)
  } finally {
    await load()
  }
}

function onToggle(item: ScheduleItem, checked: boolean | string | number) {
  void toggle(item, checked === true)
}

onMounted(() => {
  void loadTemplates()
  void load()
})
</script>

<template>
  <div class="page">
    <a-card size="small">
      <div class="status-row">
        <span>只支持一次、每天、每周和固定间隔，不提供 Cron。关闭或删除计划不会停止已经启动的实例。</span>
        <a-button :loading="loading" @click="load">刷新</a-button>
        <a-button type="primary" @click="openCreate">创建计划</a-button>
      </div>
    </a-card>
    <a-alert v-if="templateTotal > templates.length" type="info" show-icon message="模板超过 100 个，下拉只含最近更新的 100 个。" />
    <a-alert v-if="templateError" type="error" show-icon :message="templateError" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table
      size="small"
      :loading="loading"
      :columns="columns"
      :data-source="items"
      :pagination="pagination"
      :scroll="{ x: 1100 }"
      row-key="id"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'task'">{{ taskName(record.taskId) }}</template>
        <template v-else-if="column.key === 'type'">{{ scheduleTypeLabel(record.type) }}</template>
        <template v-else-if="column.key === 'rule'">{{ ruleText(record) }}</template>
        <template v-else-if="column.key === 'next'">{{ nextFireText(record.nextFireAt, record.enabled) }}</template>
        <template v-else-if="column.key === 'skip'">
          {{ skipText(record.lastSkipReason, record.lastSkipMissedCount, record.lastSkippedAt) }}
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-switch
            :checked="record.enabled"
            checked-children="开"
            un-checked-children="关"
            @change="onToggle(record, $event)"
          />
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
          <a-button type="link" size="small" danger @click="remove(record)">删除</a-button>
        </template>
      </template>
      <template #emptyText>没有计划。创建后会显示下次触发时间；发生跳过后显示最近原因。</template>
    </a-table>

    <a-modal
      :open="modalOpen"
      :title="form.id ? '编辑计划' : '创建计划'"
      :confirm-loading="saving"
      :ok-button-props="{ disabled: !!formBlockReason }"
      ok-text="保存"
      cancel-text="取消"
      width="680px"
      @ok="save"
      @cancel="modalOpen = false"
    >
      <a-alert v-if="formBlockReason" type="warning" show-icon :message="formBlockReason" style="margin-bottom: 12px" />
      <p>保存后按服务器时间重算下次触发，不影响已经启动的实例。时间输入按本机时区换算成绝对时间。</p>
      <a-form layout="vertical">
        <a-form-item label="任务模板">
          <a-select v-model:value="form.taskId" placeholder="选择有限时长模板">
            <a-select-option
              v-if="form.taskId && !templates.some((item) => item.id === form.taskId)"
              :value="form.taskId"
            >
              {{ form.taskId }}（不在当前列表）
            </a-select-option>
            <a-select-option
              v-for="task in templates"
              :key="task.id"
              :value="task.id"
              :disabled="bindReason(task, intervalForCheck) !== ''"
            >
              {{ task.name }}{{ bindReason(task, intervalForCheck) ? `（${bindReason(task, intervalForCheck)}）` : '' }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="类型">
          <a-radio-group v-model:value="form.type">
            <a-radio-button value="ONCE">一次</a-radio-button>
            <a-radio-button value="DAILY">每天</a-radio-button>
            <a-radio-button value="WEEKLY">每周</a-radio-button>
            <a-radio-button value="FIXED_INTERVAL">固定间隔</a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="form.type === 'ONCE'" label="开始时间">
          <a-input v-model:value="form.startAt" type="datetime-local" step="1" />
        </a-form-item>
        <template v-if="form.type === 'DAILY' || form.type === 'WEEKLY'">
          <a-form-item label="时区">
            <a-input v-model:value="form.timezone" placeholder="Asia/Shanghai" />
          </a-form-item>
          <a-form-item label="本地时间">
            <a-input v-model:value="form.localTime" placeholder="09:00:00" />
          </a-form-item>
          <a-form-item v-if="form.type === 'WEEKLY'" label="星期">
            <a-checkbox-group v-model:value="form.weekdays" :options="weekdayOptions" />
          </a-form-item>
        </template>
        <template v-if="form.type === 'FIXED_INTERVAL'">
          <a-form-item label="锚点">
            <a-input v-model:value="form.anchorAt" type="datetime-local" step="1" />
          </a-form-item>
          <a-form-item label="间隔（秒，至少 60）">
            <a-input-number v-model:value="form.intervalSeconds" :min="60" :precision="0" style="width: 180px" />
          </a-form-item>
        </template>
        <a-form-item label="启用">
          <a-switch v-model:checked="form.enabled" checked-children="开" un-checked-children="关" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
