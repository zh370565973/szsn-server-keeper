<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { monitor, refreshInfo, refreshResource, startStream, stopStream } from '../monitor'

const route = useRoute()
const router = useRouter()
const selected = computed(() => (route.path === '/' ? '/' : route.path))
const stopping = ref(false)

onMounted(async () => {
  await refreshInfo()
  await refreshResource().catch(() => undefined)
  startStream()
})
onUnmounted(stopStream)

function navigate(info: { key: string | number }) {
  void router.push(String(info.key))
}

async function logout() {
  await api<void>('/auth/logout', { method: 'POST' })
  stopStream()
  await router.replace('/login')
}

async function emergency() {
  stopping.value = true
  try {
    await api('/resource/emergency-stop', { method: 'POST' })
    message.success('已紧急停止，控制开关关闭并锁存')
    await refreshResource()
  } catch (caught) {
    message.error(caught instanceof Error ? caught.message : '急停失败')
  } finally {
    stopping.value = false
  }
}
</script>

<template>
  <a-layout style="min-height: 100vh">
    <a-layout-sider breakpoint="lg" collapsed-width="0" width="220" theme="dark">
      <div class="brand">资源管理</div>
      <a-menu theme="dark" mode="inline" :selected-keys="[selected]" @click="navigate">
        <a-menu-item key="/">总览</a-menu-item>
        <a-menu-item key="/resource">资源控制</a-menu-item>
        <a-menu-item key="/tasks">任务模板</a-menu-item>
        <a-menu-item key="/schedules">定时计划</a-menu-item>
        <a-menu-item key="/executions">执行记录</a-menu-item>
        <a-menu-item key="/history">历史监控</a-menu-item>
        <a-menu-item key="/settings">系统设置</a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="header">
        <div>
          <strong>监控</strong>
          <span class="hint">{{ monitor.info?.loadEngine === 'CPU' ? 'CPU 引擎已接入' : '负载引擎未安装' }}</span>
        </div>
        <div class="status-row">
          <a-tag v-if="monitor.resource?.emergencyLatched" color="error">急停已锁存</a-tag>
          <a-tag>{{ monitor.resource?.controlSwitch ?? 'OFF' }}</a-tag>
          <a-button danger :loading="stopping" @click="emergency">紧急停止</a-button>
          <a-button @click="logout">退出</a-button>
        </div>
      </a-layout-header>
      <a-layout-content style="padding: 16px">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<style scoped>
.brand {
  color: #e7eef6;
  font-weight: 700;
  padding: 18px 24px;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #18222d;
  padding: 0 16px;
}
.hint {
  margin-left: 12px;
  color: #9fb0c3;
}
</style>
