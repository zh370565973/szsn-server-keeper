import { reactive } from 'vue'
import { api, type MetricSnapshot, type ResourceStatus, type SystemInfo } from './api'

export const monitor = reactive({
  snapshot: null as MetricSnapshot | null,
  info: null as SystemInfo | null,
  resource: null as ResourceStatus | null,
  connected: false,
  error: ''
})

const points = {
  cpu: [] as number[],
  memory: [] as number[],
  net: [] as number[],
  disk: [] as number[],
  labels: [] as string[]
}

let source: EventSource | null = null
let timer: number | undefined
let delay = 1000
let stopped = true

export function series() {
  return points
}

export async function refreshCurrent() {
  monitor.snapshot = await api<MetricSnapshot>('/monitor/current')
  pushPoint(monitor.snapshot)
}

export async function refreshInfo() {
  monitor.info = await api<SystemInfo>('/system/info')
}

export async function refreshResource() {
  monitor.resource = await api<ResourceStatus>('/resource/status')
}

export function startStream() {
  stopped = false
  connect()
}

export function stopStream() {
  stopped = true
  source?.close()
  source = null
  if (timer) {
    window.clearTimeout(timer)
  }
}

function connect() {
  source?.close()
  source = new EventSource('/api/v1/monitor/stream')
  source.addEventListener('metric', (event) => {
    delay = 1000
    monitor.connected = true
    monitor.error = ''
    const snapshot = JSON.parse((event as MessageEvent).data) as MetricSnapshot
    monitor.snapshot = snapshot
    pushPoint(snapshot)
  })
  source.addEventListener('resource', (event) => {
    const pulse = JSON.parse((event as MessageEvent).data) as {
      controlSwitch?: string
      emergencyLatched?: boolean
      controlCapable?: boolean
      controlDisabledReasons?: string[]
    }
    const current = monitor.resource
    monitor.resource = {
      controlSwitch: pulse.controlSwitch ?? current?.controlSwitch ?? 'OFF',
      emergencyLatched: pulse.emergencyLatched ?? current?.emergencyLatched ?? false,
      controlCapable: pulse.controlCapable ?? current?.controlCapable ?? false,
      controlDisabledReasons: pulse.controlDisabledReasons ?? current?.controlDisabledReasons ?? [],
      loadEngine: current?.loadEngine ?? 'CPU',
      latchReason: current?.latchReason,
      healthyForMs: current?.healthyForMs,
      latchResetReady: current?.latchResetReady,
      activeExecution: current?.activeExecution,
      cpu: current?.cpu,
      memory: current?.memory,
      budget: current?.budget
    }
  })
  source.onerror = () => {
    monitor.connected = false
    source?.close()
    if (stopped) {
      return
    }
    const wait = delay
    delay = Math.min(delay * 2, 30000)
    timer = window.setTimeout(async () => {
      try {
        await refreshCurrent()
      } catch (error) {
        monitor.error = error instanceof Error ? error.message : '重连失败'
      }
      if (!stopped) {
        connect()
      }
    }, wait)
  }
}

function pushPoint(snapshot: MetricSnapshot) {
  const label = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(snapshot.observedAt)
  points.labels.push(label)
  points.cpu.push(snapshot.cpu.overallBusyPct ?? Number.NaN)
  const total = snapshot.memory.totalBytes
  const used = snapshot.memory.usedBytes
  points.memory.push(total && used !== undefined ? (used / total) * 100 : Number.NaN)
  const rx = snapshot.network.rxBytesPerSec
  const tx = snapshot.network.txBytesPerSec
  points.net.push(rx === undefined && tx === undefined ? Number.NaN : (rx ?? 0) + (tx ?? 0))
  const disk = (snapshot.disks ?? []).reduce((sum, item) => sum + (item.readBytesPerSec ?? 0) + (item.writeBytesPerSec ?? 0), 0)
  points.disk.push(snapshot.disks && snapshot.disks.some((item) => item.readBytesPerSec !== undefined) ? disk : Number.NaN)
  if (points.labels.length > 180) {
    points.labels.shift()
    points.cpu.shift()
    points.memory.shift()
    points.net.shift()
    points.disk.shift()
  }
}
