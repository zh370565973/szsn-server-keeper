import { formatTime } from './format'

const STATUS_LABEL: Record<string, string> = {
  STARTING: '启动中',
  RUNNING: '运行中',
  PAUSING: '暂停中',
  PAUSED: '已暂停',
  STOPPING: '停止中',
  STOPPED: '已停止',
  COMPLETED: '已到期完成',
  SAFETY_STOPPED: '安全停止',
  FAILED: '失败',
  INTERRUPTED: '中断',
  SKIPPED: '已跳过'
}

const REASON_LABEL: Record<string, string> = {
  USER_STOP: '用户停止',
  DURATION_ELAPSED: '到达时长',
  SAFETY_STOP: '安全停止',
  FAILED: '失败',
  ACTUAL_STOP_TIME_UNKNOWN: '实际停止时间未知',
  SKIPPED: '已跳过',
  SKIPPED_MISFIRE: '迟到超过 5 秒或服务离线，未补跑',
  SKIPPED_CONFLICT: '已有活动会话，本次跳过',
  SKIPPED_CONTROL_DISABLED: '控制关闭或急停锁存，未启动',
  TEMPLATE_UNAVAILABLE: '模板不可用',
  TEMPLATE_NOT_SCHEDULABLE: '模板不满足计划约束'
}

const WEEKDAY_LABEL = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

export function statusLabel(status?: string | null) {
  if (!status) {
    return '无数据'
  }
  return STATUS_LABEL[status] ?? status
}

export function reasonLabel(reason?: string | null) {
  if (!reason) {
    return '无'
  }
  return REASON_LABEL[reason] ?? reason
}

export function scheduleTypeLabel(type?: string | null) {
  switch (type) {
    case 'ONCE':
      return '一次'
    case 'DAILY':
      return '每天'
    case 'WEEKLY':
      return '每周'
    case 'FIXED_INTERVAL':
      return '固定间隔'
    default:
      return type || '未知类型'
  }
}

export function resourceModeLabel(mode?: string | null) {
  switch (mode) {
    case 'CPU':
      return 'CPU'
    case 'MEMORY':
      return '内存'
    case 'COMBINED':
      return '联合'
    default:
      return mode || '无数据'
  }
}

export function runModeLabel(mode?: string | null) {
  switch (mode) {
    case 'FINITE':
      return '限时'
    case 'CONTINUOUS':
      return '持续'
    default:
      return mode || '无数据'
  }
}

export function memoryModeLabel(mode?: string | null) {
  switch (mode) {
    case 'EXTRA_BYTES':
      return '额外容量'
    case 'SYSTEM_PERCENT':
      return '系统占用'
    default:
      return mode || '无'
  }
}

export function weekdayText(days?: number[] | null) {
  if (!days?.length) {
    return '未选星期'
  }
  return [...days].sort((left, right) => left - right).map((day) => WEEKDAY_LABEL[day] ?? String(day)).join('、')
}

export function nextFireText(at?: number | null, enabled?: boolean) {
  if (!enabled) {
    return at ? `已关闭，保留 ${formatTime(at)}` : '已关闭，没有下次触发'
  }
  return at ? formatTime(at) : '没有下次触发'
}

export function skipText(reason?: string | null, missedCount?: number | null, at?: number | null) {
  if (!reason && at == null) {
    return '尚无跳过'
  }
  const text = reason ? reasonLabel(reason) : '已跳过'
  const missed = missedCount && missedCount > 0 ? `（遗漏 ${missedCount} 次）` : ''
  return at ? `${text}${missed}，${formatTime(at)}` : `${text}${missed}`
}

export const EXECUTION_STATUSES = Object.keys(STATUS_LABEL)
