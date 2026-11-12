<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { monitor, refreshInfo, refreshResource, startStream, stopStream } from '../monitor'

const route = useRoute()
const router = useRouter()
const stopping = ref(false)
const siderCollapsed = ref(false)
const siderWidth = 220

const menus = [
  {
    key: 'monitor',
    label: '运行监控',
    children: [
      { key: '/', label: '总览' },
      { key: '/history', label: '历史监控' }
    ]
  },
  {
    key: 'load',
    label: '负载控制',
    children: [{ key: '/resource', label: '资源控制' }]
  },
  {
    key: 'jobs',
    label: '任务调度',
    children: [
      { key: '/tasks', label: '任务模板' },
      { key: '/schedules', label: '定时计划' },
      { key: '/executions', label: '执行记录' }
    ]
  },
  {
    key: 'system',
    label: '系统',
    children: [
      { key: '/settings', label: '系统设置' },
      { key: '/guide', label: '使用说明' }
    ]
  }
]

const activeTop = computed(() => {
  const found = menus.find((menu) => menu.children.some((child) => child.key === route.path))
  return found?.key ?? menus[0].key
})
const sideMenus = computed(() => menus.find((menu) => menu.key === activeTop.value)?.children ?? [])
const selected = computed(() => (route.path === '/' ? '/' : route.path))

function openTop(key: string) {
  const menu = menus.find((item) => item.key === key)
  if (!menu || menu.children.some((child) => child.key === route.path)) {
    return
  }
  void router.push(menu.children[0].key)
}

onMounted(async () => {
  await refreshInfo()
  await refreshResource().catch(() => undefined)
  startStream()
})
onUnmounted(stopStream)

function navigate(info: { key: string | number }) {
  void router.push(String(info.key))
}

function onSiderCollapse(collapsed: boolean) {
  siderCollapsed.value = collapsed
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
  <a-layout class="shell">
    <a-layout-header class="header">
      <div class="brand" :class="{ collapsed: siderCollapsed }" :style="siderCollapsed ? undefined : { width: `${siderWidth}px` }">
        <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
          <rect x="3" y="5" width="26" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="1.8" />
          <path d="M11 25h10M16 21v4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <rect x="7" y="13" width="4" height="5" rx="0.6" fill="currentColor" opacity="0.85" />
          <rect x="14" y="9" width="4" height="9" rx="0.6" fill="currentColor" />
          <rect x="21" y="11" width="4" height="7" rx="0.6" fill="currentColor" opacity="0.7" />
        </svg>
        <span class="brand-name">服务器资源管理</span>
      </div>
      <nav class="top-nav" aria-label="主菜单">
        <button
          v-for="item in menus"
          :key="item.key"
          type="button"
          class="top-item"
          :class="{ active: item.key === activeTop }"
          @click="openTop(item.key)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="status-row">
        <span class="hint">{{
          monitor.info?.loadEngine === 'CPU_MEMORY' || monitor.info?.loadEngine === 'CPU'
            ? 'CPU/内存引擎已接入'
            : '负载引擎未安装'
        }}</span>
        <a-tag v-if="monitor.resource?.emergencyLatched" color="error">急停已锁存</a-tag>
        <a-tag>{{ monitor.resource?.controlSwitch ?? 'OFF' }}</a-tag>
        <a-button danger :loading="stopping" @click="emergency">紧急停止</a-button>
        <a-button class="logout" @click="logout">退出</a-button>
      </div>
    </a-layout-header>
    <a-layout class="body">
      <a-layout-sider
        breakpoint="lg"
        collapsed-width="0"
        :width="siderWidth"
        theme="light"
        @collapse="onSiderCollapse"
      >
        <div class="side-title">{{ menus.find((item) => item.key === activeTop)?.label }}</div>
        <a-menu theme="light" mode="inline" :selected-keys="[selected]" @click="navigate">
          <a-menu-item v-for="item in sideMenus" :key="item.key">{{ item.label }}</a-menu-item>
        </a-menu>
      </a-layout-sider>
      <a-layout-content class="content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<style scoped>
.shell,
.body {
  background: #f5f7f9;
}
.shell {
  height: 100vh;
  overflow: hidden;
}
.body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.header {
  display: flex;
  align-items: stretch;
  gap: 8px;
  height: 56px;
  line-height: 56px;
  padding: 0 16px 0 0;
  background: #0c7cff;
  color: #fff;
}
.brand {
  box-sizing: border-box;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  padding: 0 16px 0 24px;
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  line-height: 1.2;
}
.brand.collapsed {
  width: auto;
  padding-left: 16px;
}
.brand-name {
  min-width: 0;
}
.brand-mark {
  width: 28px;
  height: 28px;
  flex: none;
}
.top-nav {
  display: flex;
  align-items: stretch;
  min-width: 0;
  margin-left: 12px;
}
.top-item {
  margin: 0;
  padding: 0 18px;
  border: 0;
  border-bottom: 3px solid transparent;
  background: transparent;
  color: rgba(255, 255, 255, 0.88);
  font: inherit;
  font-size: 15px;
  line-height: 53px;
  cursor: pointer;
  white-space: nowrap;
}
.top-item:hover,
.top-item.active {
  color: #fff;
  font-weight: 700;
  background: rgba(255, 255, 255, 0.16);
}
.top-item.active {
  border-bottom-color: #fff;
}
.side-title {
  padding: 14px 24px 6px;
  color: #8c8c8c;
  font-size: 12px;
  line-height: 1.4;
}
.hint {
  color: rgba(255, 255, 255, 0.88);
}
.status-row {
  flex: none;
  margin-left: auto;
  align-self: center;
  flex-wrap: nowrap;
}
.content {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: auto;
  padding: 12px;
  background: #f5f7f9;
}
.header :deep(.logout.ant-btn) {
  color: #fff;
  background: transparent;
  border-color: rgba(255, 255, 255, 0.65);
}
.header :deep(.logout.ant-btn:not(:disabled):hover) {
  color: #0c7cff;
  background: #fff;
  border-color: #fff;
}
</style>
