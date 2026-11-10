<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, loadCsrf } from '../api'

const router = useRouter()
const form = reactive({ username: 'admin', password: '' })
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await loadCsrf()
    await api('/auth/login', { method: 'POST', body: JSON.stringify(form) })
    await router.replace('/')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <a-card title="服务器资源管理" class="card">
      <p>查看本机真实资源。本阶段只提供只读监控，不能启动负载。</p>
      <a-form layout="vertical" @submit.prevent="submit">
        <a-form-item label="用户名">
          <a-input v-model:value="form.username" autocomplete="username" />
        </a-form-item>
        <a-form-item label="口令">
          <a-input-password v-model:value="form.password" autocomplete="current-password" />
        </a-form-item>
        <a-alert v-if="error" type="error" :message="error" show-icon />
        <a-button type="primary" html-type="submit" block :loading="loading" style="margin-top: 12px">登录</a-button>
      </a-form>
    </a-card>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}
.card {
  width: min(420px, 100%);
}
</style>
