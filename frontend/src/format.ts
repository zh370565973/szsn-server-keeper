const TIME_ZONE = 'Asia/Shanghai'

export function formatBytes(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '无数据'
  }
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let size = value
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

export function formatPercent(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '无数据'
  }
  return `${value.toFixed(1)}%`
}

export function formatRate(value?: number | null) {
  if (value === undefined || value === null) {
    return '无数据'
  }
  return `${formatBytes(value)}/s`
}

export function formatTime(epoch?: number | null) {
  if (!epoch) {
    return '无数据'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: TIME_ZONE,
    hour12: false,
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(epoch)
}

export function formatMillis(value?: number | null) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '无数据'
  }
  if (value < 1000) {
    return `${value} 毫秒`
  }
  if (value < 60_000) {
    const seconds = value / 1000
    return `${seconds.toFixed(seconds < 10 ? 1 : 0)} 秒`
  }
  return formatDuration(Math.floor(value / 1000))
}

export function toLocalInput(epoch?: number | null) {
  if (!epoch) {
    return ''
  }
  const date = new Date(epoch)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function epochFromLocal(value?: string | null) {
  if (!value) {
    return null
  }
  const parsed = new Date(value).getTime()
  return Number.isFinite(parsed) ? parsed : null
}

export function formatDuration(seconds?: number | null) {
  if (seconds === undefined || seconds === null) {
    return '无数据'
  }
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) {
    return `${days} 天 ${hours} 小时`
  }
  if (hours > 0) {
    return `${hours} 小时 ${minutes} 分钟`
  }
  return `${minutes} 分钟`
}

export function qualityLabel(quality?: string) {
  switch (quality) {
    case 'VALID':
      return '有效'
    case 'PARTIAL':
      return '部分缺失'
    case 'STALE':
      return '数据过期'
    case 'UNAVAILABLE':
      return '不可用'
    default:
      return '无数据'
  }
}
