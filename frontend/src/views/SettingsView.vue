<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type SettingsView } from '../api'
import { monitor } from '../monitor'

const settings = ref<SettingsView>()
const error = ref('')

onMounted(async () => {
  try {
    settings.value = await api<SettingsView>('/settings')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法读取设置'
  }
})
</script>

<template>
  <div class="page">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-card title="控制开关" size="small">
      <p>当前 {{ settings?.controlSwitch ?? 'OFF' }}。{{ settings?.controlSwitchPolicy }}</p>
      <a-tooltip title="M1 未安装负载引擎，不能打开开关，也不能解除锁存">
        <a-button disabled>打开控制</a-button>
      </a-tooltip>
      <a-tooltip title="当前没有急停锁存">
        <a-button disabled style="margin-left: 8px">解除锁存</a-button>
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
