<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { api, setUnauthorizedHandler } from './api'

const router = useRouter()
const ready = ref(false)
const uiTheme = {
  algorithm: theme.defaultAlgorithm,
  token: {
    colorPrimary: '#0C7CFF',
    colorBgLayout: '#f5f7f9',
    borderRadius: 6,
    fontSize: 15,
    fontSizeSM: 14,
    fontFamily: '"Microsoft YaHei", "微软雅黑", "PingFang SC", sans-serif'
  },
  components: {
    Layout: {
      headerBg: '#0C7CFF',
      headerColor: '#ffffff',
      headerHeight: 56,
      bodyBg: '#f5f7f9',
      siderBg: '#ffffff',
      lightSiderBg: '#ffffff'
    },
    Menu: {
      itemBg: '#ffffff',
      itemColor: '#3e3e3e',
      itemHoverBg: '#f5f7fa',
      itemSelectedBg: '#e6f7ff',
      itemSelectedColor: '#0C7CFF'
    }
  }
}

setUnauthorizedHandler(() => {
  if (router.currentRoute.value.path !== '/login') {
    void router.push('/login')
  }
})

onMounted(async () => {
  try {
    await api('/auth/me')
    if (router.currentRoute.value.path === '/login') {
      await router.replace('/')
    }
  } catch {
    if (router.currentRoute.value.path !== '/login') {
      await router.replace('/login')
    }
  } finally {
    ready.value = true
  }
})
</script>

<template>
  <a-config-provider :locale="zhCN" :theme="uiTheme">
    <router-view v-if="ready" />
  </a-config-provider>
</template>
