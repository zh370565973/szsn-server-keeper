<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { api, type ResourceStatus, type SettingsView } from '../api'
import { monitor } from '../monitor'

const settings = ref<SettingsView>()
const status = ref<ResourceStatus>()
const error = ref('')
const busy = ref(false)
const healthySeconds = computed(() => Math.floor((status.value?.healthyForMs ?? 0) / 1000))

onMounted(load)

async function load() {
  try {
    settings.value = await api<SettingsView>('/settings')
    status.value = await api<ResourceStatus>('/resource/status')
    error.value = ''
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法读取设置'
  }
}

async function setSwitch(enabled: boolean) {
  busy.value = true
  error.value = ''
  try {
    await api('/resource/control-switch', { method: 'PUT', body: JSON.stringify({ enabled }) })
    message.success(enabled ? '控制开关已打开。这不会启动负载。' : '控制开关已关闭')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '开关更新失败'
  } finally {
    busy.value = false
  }
}

async function resetLatch() {
  busy.value = true
  error.value = ''
  try {
    await api('/resource/reset-latch', { method: 'POST' })
    message.success('锁存已解除，控制开关仍为关闭')
    await load()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '不能解除锁存'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-card title="控制开关" size="small">
      <p>当前 {{ status?.controlSwitch ?? settings?.controlSwitch ?? 'OFF' }}。{{ settings?.controlSwitchPolicy }}</p>
      <p v-if="status?.emergencyLatched">
        已锁存，原因 {{ status.latchReason || '未记录' }}。关键指标已连续健康 {{ healthySeconds }} 秒，解除需要 30 秒，且没有活动会话。
      </p>
      <p v-else>当前没有急停锁存。打开开关只允许以后启动，不会补跑计划，也不会启动引擎。</p>
      <a-button :loading="busy" @click="setSwitch((status?.controlSwitch ?? 'OFF') !== 'ON')">
        {{ (status?.controlSwitch ?? 'OFF') === 'ON' ? '关闭控制' : '打开控制' }}
      </a-button>
      <a-tooltip :title="status?.emergencyLatched ? '直接解除，不满足条件时会拒绝' : '当前没有急停锁存'">
        <a-button :disabled="!status?.emergencyLatched" :loading="busy" style="margin-left: 8px" @click="resetLatch">
          解除锁存
        </a-button>
      </a-tooltip>
    </a-card>
    <a-card title="只读阈值" size="small">
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item v-for="(value, key) in settings?.thresholds ?? {}" :key="key" :label="String(key)">
          {{ value }}
        </a-descriptions-item>
      </a-descriptions>
    </a-card>
    <a-card title="部署位置" size="small">
      <p>监听 {{ settings?.listen }}，时区 {{ settings?.timezone }}。Web 不写入这些配置。</p>
      <ul>
        <li v-for="(value, key) in settings?.deploy ?? {}" :key="key">{{ key }}：{{ value }}</li>
      </ul>
      <p>存储状态：{{ monitor.info?.storageHealthy === false ? monitor.info?.storageFault : '可写' }}</p>
    </a-card>
  </div>
</template>
