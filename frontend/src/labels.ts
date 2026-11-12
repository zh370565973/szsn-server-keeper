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
  EMERGENCY_STOP: '紧急停止',
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
  TEMPLATE_NOT_SCHEDULABLE: '模板不满足计划约束',
  START_FAILED: '启动失败',
  CPU_GE_94: 'CPU 达到 94%，已停止加力',
  CPU_GE_97: 'CPU 达到 97%，已停止并锁存',
  CPU_GE_85: 'CPU 过高，已停止加力',
  CPU_GE_95: 'CPU 过高，已停止并锁存',
  CPU_SAMPLE_MISSING: '没有 CPU 采样',
  EXTERNAL_LOAD_HIGH: '其他程序已经占满 CPU',
  SAFETY_ZERO: '安全保护已把出力降为 0',
  FORBID_MEMORY_GROWTH: '安全保护禁止继续占内存',
  TARGET_LIMITED: '目标受限，预算不够继续加',
  SYSTEM_ABOVE_TARGET: '系统占用已高于目标',
  REGROW_COOLDOWN: '刚释放内存，暂时不再增加',
  DEAD_ZONE: '已在目标附近',
  SAMPLE_INCOMPLETE: '内存采样不完整',
  HEAP_MAX_UNAVAILABLE: '堆上限未知',
  HEAP_AVAILABLE: '堆空间不足',
  HEAP_GE_80: '堆使用过高，不再增加内存',
  HEAP_GE_85: '堆使用过高，已停止并锁存',
  HEAP_GE_93: '堆使用达到 93%，不再增加内存',
  HEAP_GE_95: '堆使用达到 95%，已停止并锁存',
  SYSTEM_AVAILABLE: '系统可用内存不足',
  TASK_CAP: '达到任务上限',
  ALIGNMENT: '容量不足以按 4 MiB 对齐',
  NO_BUDGET: '当前没有可新增的内存',
  AVAILABLE_BELOW_RESERVE: '可用内存低于保留线，已停止并锁存',
  AVAILABLE_BELOW_RESERVE_GUARD: '可用内存接近保留线，不再增加',
  METRIC_STALE_3S: '监控数据超过 3 秒，暂停增加',
  METRIC_STALE_10S: '监控数据超过 10 秒，已停止并锁存',
  SWAP_RATE: '换页过快，已停止并锁存',
  SWAP_RATE_UNKNOWN: '换页速率未知',
  STEAL_OR_IOWAIT: '虚拟化抢占或磁盘等待过高',
  CPU_NOT_FRESH: 'CPU 采样不够新',
  MEMORY_NOT_FRESH: '内存采样不够新',
  HEAP_UNKNOWN: '堆使用量未知',
  STORAGE_UNAVAILABLE: '数据库不可写'
}

const WEEKDAY_LABEL = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

export function statusLabel(status?: string | null) {
  if (!status) {
    return '无数据'
  }
  return STATUS_LABEL[status] ?? status
}

export function switchLabel(value?: string | null) {
  return value === 'ON' ? '开' : '关'
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
