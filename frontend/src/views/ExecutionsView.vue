<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, explainError, type ExecutionDetail, type ExecutionSummary, type PageResult } from '../api'
import { epochFromLocal, formatBytes, formatMillis, formatPercent, formatTime } from '../format'
import { EXECUTION_STATUSES, reasonLabel, statusLabel } from '../labels'

const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const detailError = ref('')
const drawerOpen = ref(false)
const items = ref<ExecutionSummary[]>([])
const detail = ref<ExecutionDetail>()
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const filters = reactive({
  status: '',
  taskId: '',
  from: '',
  to: ''
})

const columns = [
  { title: '名称', key: 'name' },
  { title: '状态', key: 'status', width: 120 },
  { title: '开始', key: 'started', width: 160 },
  { title: '结束', key: 'ended', width: 160 },
  { title: '原因', key: 'reason' },
  { title: '操作', key: 'actions', width: 90 }
]

const statLabels: Record<string, string> = {
  validCoverageMs: '有效覆盖时长',
  cpuBusyAvgPct: 'CPU 均值',
  cpuBusyPeakPct: 'CPU 峰值',
  cpuBusyCoverageMs: 'CPU 有效时长',
  systemMemoryUsedAvgBytes: '整机内存使用均值',
  systemMemoryUsedPeakBytes: '整机内存使用峰值',
  systemMemoryUsedCoverageMs: '整机内存有效时长',
  heldBytesAvg: '负载持有均值',
  heldBytesPeak: '负载持有峰值',
  heldBytesCoverageMs: '负载持有有效时长',
  missedCount: '遗漏次数'
}

const snapshotLabels: Record<string, string> = {
  name: '名称',
  resourceMode: '资源',
  cpuTargetPct: 'CPU 目标',
  memoryMode: '内存方式',
  memoryTargetBytes: '额外容量',
  memoryTargetPct: '系统占用目标',
  runMode: '运行方式',
  durationSeconds: '时长（秒）'
}

const pagination = computed(() => ({
  current: page.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (value: number) => `共 ${value} 条`
}))

const snapshotText = computed(() => {
  const raw = detail.value?.configSnapshotJson ?? ''
  if (!detail.value?.configSnapshotReadable) {
    return raw || '无快照'
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

const statEntries = computed(() => {
  const stats = detail.value?.stats ?? {}
  const keys = Object.keys(stats)
  const ordered = [...Object.keys(statLabels).filter((key) => keys.includes(key)), ...keys.filter((key) => !statLabels[key])]
  return ordered.map((key) => ({ key, label: statLabels[key] ?? key, value: stats[key] }))
})

function statText(key: string, value: unknown) {
  if (typeof value !== 'number') {
    return value == null ? '无数据' : String(value)
  }
  if (key.endsWith('Pct')) {
    return formatPercent(value)
  }
  if (key.endsWith('Bytes') || key.startsWith('heldBytes')) {
    return formatBytes(value)
  }
  if (key.endsWith('Ms')) {
    return formatMillis(value)
  }
  return String(value)
}

function snapshotValue(key: string, value: unknown) {
  if (key === 'memoryTargetBytes' && typeof value === 'number') {
    return formatBytes(value)
  }
  if (key === 'cpuTargetPct' || key === 'memoryTargetPct') {
    return typeof value === 'number' ? `${value}%` : String(value)
  }
  return value == null ? '无' : String(value)
}

function displayName(item: ExecutionSummary) {
  return item.name || item.id
}

function reasonText(item: ExecutionSummary) {
  const text = item.reason ? reasonLabel(item.reason) : '无'
  return item.missedCount ? `${text}（遗漏 ${item.missedCount} 次）` : text
}

async function load() {
  const from = epochFromLocal(filters.from)
  const to = epochFromLocal(filters.to)
  if (filters.from && from == null) {
    error.value = '开始时间无法识别'
    return
  }
  if (filters.to && to == null) {
    error.value = '结束时间无法识别'
    return
  }
  if (from != null && to != null && from > to) {
    error.value = '开始时间不能晚于结束时间'
    return
  }
  const taskId = filters.taskId.trim()
  if (taskId.length > 36) {
    error.value = '模板编号不合法'
    return
  }
  loading.value = true
  error.value = ''
  const params = new URLSearchParams()
  params.set('page', String(page.value))
  params.set('pageSize', String(pageSize.value))
  if (filters.status) {
    params.set('status', filters.status)
  }
  if (taskId) {
    params.set('taskId', taskId)
  }
  if (from != null) {
    params.set('from', String(from))
  }
  if (to != null) {
    params.set('to', String(to))
  }
  try {
    const result = await api<PageResult<ExecutionSummary>>('/executions?' + params.toString())
    items.value = result.items
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
  } catch (caught) {
    error.value = explainError(caught, '执行记录加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function resetFilters() {
  filters.status = ''
  filters.taskId = ''
  filters.from = ''
  filters.to = ''
  page.value = 1
  void load()
}

function onTableChange(pager: { current?: number; pageSize?: number }) {
  const nextSize = pager.pageSize ?? pageSize.value
  page.value = nextSize === pageSize.value ? pager.current ?? 1 : 1
  pageSize.value = nextSize
  void load()
}

async function openDetail(item: ExecutionSummary) {
  drawerOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = undefined
  try {
    detail.value = await api<ExecutionDetail>('/executions/' + encodeURIComponent(item.id))
  } catch (caught) {
    detailError.value = explainError(caught, '执行详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <a-card size="small">
      <div class="status-row">
        <a-select v-model:value="filters.status" allow-clear placeholder="全部状态" style="width: 160px">
          <a-select-option value="">全部状态</a-select-option>
          <a-select-option v-for="status in EXECUTION_STATUSES" :key="status" :value="status">
            {{ statusLabel(status) }}
          </a-select-option>
        </a-select>
        <a-input v-model:value="filters.taskId" placeholder="模板编号" style="width: 280px" allow-clear />
        <a-input v-model:value="filters.from" type="datetime-local" step="1" style="width: 210px" />
        <span>至</span>
        <a-input v-model:value="filters.to" type="datetime-local" step="1" style="width: 210px" />
        <a-button type="primary" :loading="loading" @click="search">查询</a-button>
        <a-button @click="resetFilters">重置</a-button>
      </div>
      <p>时间按开始、结束或计划触发时间过滤。整机内存使用和负载持有分开显示，不混称内存占用。</p>
    </a-card>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table
      size="small"
      :loading="loading"
      :columns="columns"
      :data-source="items"
      :pagination="pagination"
      :scroll="{ x: 900 }"
      row-key="id"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ displayName(record) }}</template>
        <template v-else-if="column.key === 'status'">{{ statusLabel(record.status) }}</template>
        <template v-else-if="column.key === 'started'">{{ formatTime(record.startedAt) }}</template>
        <template v-else-if="column.key === 'ended'">{{ formatTime(record.endedAt) }}</template>
        <template v-else-if="column.key === 'reason'">{{ reasonText(record) }}</template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
        </template>
      </template>
      <template #emptyText>没有符合条件的执行记录</template>
    </a-table>

    <a-drawer :open="drawerOpen" title="执行详情" width="640" @close="drawerOpen = false">
      <a-spin :spinning="detailLoading">
        <a-alert v-if="detailError" type="error" show-icon :message="detailError" />
        <template v-else-if="detail">
          <a-alert
            v-if="detail.execution.status === 'INTERRUPTED'"
            type="warning"
            show-icon
            message="这条记录在重启时被标为中断。实际停止时间未知，下面的时长不是精确运行时间。"
            style="margin-bottom: 12px"
          />
          <a-descriptions :column="1" size="small" bordered>
            <a-descriptions-item label="状态">{{ statusLabel(detail.execution.status) }}</a-descriptions-item>
            <a-descriptions-item label="原因">{{ reasonText(detail.execution) }}</a-descriptions-item>
            <a-descriptions-item label="开始">{{ formatTime(detail.execution.startedAt) }}</a-descriptions-item>
            <a-descriptions-item label="结束">{{ formatTime(detail.execution.endedAt) }}</a-descriptions-item>
            <a-descriptions-item label="已过去">{{ formatMillis(detail.execution.elapsedDurationMs) }}</a-descriptions-item>
            <a-descriptions-item label="有效运行">{{ formatMillis(detail.execution.activeDurationMs) }}</a-descriptions-item>
            <a-descriptions-item label="最后心跳">{{ formatTime(detail.execution.lastHeartbeatAt) }}</a-descriptions-item>
            <a-descriptions-item label="来源">
              {{ detail.execution.requestSource === 'SCHEDULE' ? '计划触发' : detail.execution.requestSource || '手动或未记录' }}
            </a-descriptions-item>
            <a-descriptions-item label="模板编号">{{ detail.execution.taskId || '无' }}</a-descriptions-item>
            <a-descriptions-item label="计划编号">{{ detail.execution.scheduleId || '无' }}</a-descriptions-item>
          </a-descriptions>

          <h3>配置快照</h3>
          <a-alert
            v-if="!detail.configSnapshotReadable"
            type="error"
            show-icon
            message="快照不是合法 JSON，下面保留原文。"
          />
          <a-descriptions v-else-if="detail.configSnapshot" :column="1" size="small">
            <a-descriptions-item v-for="(value, key) in detail.configSnapshot" :key="key" :label="snapshotLabels[key] ?? key">
              {{ snapshotValue(String(key), value) }}
            </a-descriptions-item>
          </a-descriptions>
          <pre class="snapshot">{{ snapshotText }}</pre>

          <h3>有效统计</h3>
          <a-alert v-if="!detail.statsReadable" type="error" show-icon message="统计摘要无法读取" />
          <p v-else-if="statEntries.length === 0">还没有有效统计。空采样不计入均值。</p>
          <a-descriptions v-else :column="1" size="small">
            <a-descriptions-item v-for="entry in statEntries" :key="entry.key" :label="entry.label">
              {{ statText(entry.key, entry.value) }}
            </a-descriptions-item>
          </a-descriptions>

          <h3>安全事件</h3>
          <p v-if="detail.safetyEvents.length === 0">没有安全事件</p>
          <ul v-else class="reason-list">
            <li v-for="(event, index) in detail.safetyEvents" :key="index">
              {{ reasonLabel(event.reason) }}，{{ formatTime(event.at) }}
            </li>
          </ul>

          <h3>目标变更</h3>
          <p v-if="detail.targetChanges.length === 0">没有已保存的目标变更。运行中修改目标不会写入这条记录。</p>
          <ul v-else class="reason-list">
            <li v-for="(change, index) in detail.targetChanges" :key="index">
              {{ change.field || '目标' }} {{ change.value ?? '无数据' }}，版本 {{ change.fromVersion ?? '无' }} → {{ change.toVersion ?? '无' }}，{{ formatTime(change.at) }}
            </li>
          </ul>
        </template>
        <p v-else-if="!detailLoading">还没有打开记录</p>
      </a-spin>
    </a-drawer>
  </div>
</template>

<style scoped>
h3 {
  margin: 16px 0 8px;
}
.snapshot {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 8px 0 0;
  font-family: Consolas, "Courier New", monospace;
}
</style>
