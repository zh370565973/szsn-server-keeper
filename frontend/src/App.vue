<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { api, setUnauthorizedHandler } from './api'

const router = useRouter()
const ready = ref(false)
const uiTheme = {
  algorithm: theme.darkAlgorithm,
  token: { colorPrimary: '#3cbaa2', colorBgBase: '#10151c', borderRadius: 8 }
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
