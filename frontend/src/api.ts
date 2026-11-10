export interface ApiErrorBody {
  code?: string
  message?: string
  details?: { reasons?: string[] }
}

export interface HostView {
  hostname?: string
  osName?: string
  osVersion?: string
  kernel?: string
  cpuModel?: string
  logicalCpus?: number
  jdkVersion?: string
  systemUptimeSeconds?: number
  processUptimeSeconds?: number
  processUptimeMs?: number
}

export interface CpuView {
  overallBusyPct?: number
  userPct?: number
  systemPct?: number
  idlePct?: number
  iowaitPct?: number
  stealPct?: number
  load1?: number
  load5?: number
  load15?: number
}

export interface MemoryView {
  totalBytes?: number
  availableBytes?: number
  usedBytes?: number
  cacheBytes?: number
  swapTotalBytes?: number
  swapUsedBytes?: number
}

export interface JvmView {
  heapUsedBytes?: number
  heapCommittedBytes?: number
  heapMaxBytes?: number
  nonHeapUsedBytes?: number
  gcCount?: number
  gcTimeMs?: number
  threadCount?: number
  rssBytes?: number
  processCpuPct?: number
}

export interface MountView {
  mount?: string
  totalBytes?: number
  usedBytes?: number
  usedPct?: number
}

export interface DiskView {
  name?: string
  readBytesPerSec?: number
  writeBytesPerSec?: number
}

export interface NicView {
  name?: string
  status?: string
  rxBytesPerSec?: number
  txBytesPerSec?: number
  rxBytes?: number
  txBytes?: number
}

export interface MetricSnapshot {
  sequence: number
  bootId: string
  observedAt: number
  ageMs: number
  quality: 'VALID' | 'PARTIAL' | 'STALE' | 'UNAVAILABLE'
  missingFields?: string[]
  criticalComplete: boolean
  host: HostView
  cpu: CpuView
  memory: MemoryView
  jvm: JvmView
  mounts?: MountView[]
  disks?: DiskView[]
  network: {
    rxBytesPerSec?: number
    txBytesPerSec?: number
    rxBytes?: number
    txBytes?: number
    interfaces?: NicView[]
  }
}

export interface SystemInfo {
  hostname?: string
  osName?: string
  osVersion?: string
  kernel?: string
  cpuModel?: string
  logicalCpus?: number
  jdkVersion?: string
  memTotalBytes?: number
  cgroupVersion?: string
  controlCapable: boolean
  controlDisabledReasons: string[]
  dataDiskFreeBytes?: number
  dataDiskFreeRatio?: number
  storageHealthy: boolean
  storageFault?: string
  phase: string
  loadEngine: string
  acceptance: string
}

export interface ResourceStatus {
  controlSwitch: string
  emergencyLatched: boolean
  latchReason?: string
  latchPersisted?: boolean
  healthyForMs?: number
  healthReasons?: string[]
  latchResetReady?: boolean
  storageHealthy?: boolean
  activeExecution?: { executionId: string; status: string; heldBytes: number } | null
  controlCapable: boolean
  controlDisabledReasons: string[]
  loadEngine: string
}

export interface HistoryPoint {
  bucketStart: number
  sampleCount: number
  coverageMs: number
  metrics: Record<string, number>
}

export interface HistoryView {
  from: number
  to: number
  bucketSeconds: number
  buckets: HistoryPoint[]
}

export interface SettingsView {
  timezone: string
  listen: string
  controlSwitch: string
  controlSwitchPolicy: string
  emergencyLatched: boolean
  latchReason?: string
  healthyForMs?: number
  latchResetReady?: boolean
  thresholdsReadOnly: boolean
  thresholds: Record<string, string | number | boolean>
  deploy: Record<string, string>
}

export class ApiError extends Error {
  status: number
  code?: string
  details?: ApiErrorBody['details']

  constructor(status: number, message: string, code?: string, details?: ApiErrorBody['details']) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

let csrf: { headerName: string; token: string } | null = null
let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

export async function loadCsrf() {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  const body = await response.json()
  if (!response.ok) {
    throw new ApiError(response.status, body?.error?.message ?? '无法获取 CSRF')
  }
  csrf = body.data
  return csrf
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (!['GET', 'HEAD'].includes(method)) {
    if (!csrf) {
      await loadCsrf()
    }
    if (csrf) {
      headers.set(csrf.headerName, csrf.token)
    }
  }
  const response = await fetch('/api/v1' + path, { ...options, headers, credentials: 'include' })
  if (response.status === 204) {
    return undefined as T
  }
  const body = await response.json().catch(() => null)
  if (response.status === 401 && !path.startsWith('/auth/login') && !path.startsWith('/auth/csrf')) {
    onUnauthorized?.()
  }
  if (!response.ok) {
    throw new ApiError(response.status, body?.error?.message ?? '请求失败', body?.error?.code, body?.error?.details)
  }
  return body.data as T
}
