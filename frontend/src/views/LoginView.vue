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
    <section class="banner">
      <svg class="bg-grid" viewBox="0 0 1000 800" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        <defs>
          <pattern id="login-dots" width="22" height="22" patternUnits="userSpaceOnUse">
            <circle cx="1.2" cy="1.2" r="1.15" fill="currentColor" />
          </pattern>
          <pattern id="login-mesh" width="88" height="88" patternUnits="userSpaceOnUse">
            <path d="M0 44H88M44 0V88" fill="none" stroke="currentColor" stroke-width="1" />
          </pattern>
        </defs>
        <rect class="mesh" width="1000" height="800" fill="url(#login-mesh)" />
        <rect class="dots" width="1000" height="800" fill="url(#login-dots)" />
        <g class="trace" fill="none" stroke="currentColor" stroke-width="1.4">
          <path d="M36 210H150V118H248" />
          <path d="M36 250H96" />
          <path d="M760 150H900V250H964" />
          <path d="M820 150V78H940" />
          <path d="M70 640H190V560H300" />
          <path d="M700 700H860V610H960" />
        </g>
        <g fill="currentColor">
          <circle cx="248" cy="118" r="3.2" />
          <circle cx="964" cy="250" r="3.2" />
          <circle cx="300" cy="560" r="3.2" />
          <circle cx="700" cy="700" r="3.2" />
          <circle cx="940" cy="78" r="3.2" />
        </g>
      </svg>
      <div class="vignette" aria-hidden="true"></div>
      <div class="sparks" aria-hidden="true">
        <i class="spark s1"></i>
        <i class="spark diamond s2"></i>
        <i class="spark s3"></i>
        <i class="spark diamond s4"></i>
        <i class="spark s5"></i>
        <i class="spark s6"></i>
        <i class="spark diamond s7"></i>
      </div>
      <div class="scan" aria-hidden="true"></div>
      <div class="banner-title">服务器资源管理</div>
      <div class="banner-body">
        <svg class="scene" viewBox="0 0 480 380" aria-hidden="true">
          <defs>
            <radialGradient id="login-core-glow" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stop-color="#fff" stop-opacity="0.7" />
              <stop offset="58%" stop-color="#fff" stop-opacity="0.1" />
              <stop offset="100%" stop-color="#fff" stop-opacity="0" />
            </radialGradient>
          </defs>
          <g transform="translate(240 190)">
            <g class="spin slow">
              <circle r="164" fill="none" stroke="transparent" />
              <circle r="158" fill="none" stroke="currentColor" stroke-width="1.2" stroke-dasharray="1.6 39.6" />
              <rect x="-5" y="-172" width="10" height="10" rx="2" fill="currentColor" />
              <rect x="-5" y="-172" width="10" height="10" rx="2" fill="currentColor" transform="rotate(120)" />
              <rect x="-5" y="-172" width="10" height="10" rx="2" fill="currentColor" transform="rotate(240)" />
            </g>
            <g class="spin reverse">
              <circle r="118" fill="none" stroke="transparent" />
              <circle r="112" fill="none" stroke="currentColor" stroke-width="1.25" stroke-dasharray="16 12 3 12" opacity="0.75" />
            </g>
            <g class="radar">
              <circle r="164" fill="none" stroke="transparent" />
              <circle
                r="164"
                fill="none"
                stroke="currentColor"
                stroke-width="3"
                stroke-linecap="round"
                stroke-dasharray="78 954"
                opacity="0.95"
              />
            </g>
            <circle class="halo" r="74" fill="url(#login-core-glow)" />
            <polygon class="hex" points="62,0 31,53.7 -31,53.7 -62,0 -31,-53.7 31,-53.7" />
            <polygon class="hex inner" points="40,0 20,34.6 -20,34.6 -40,0 -20,-34.6 20,-34.6" />
            <g class="bars" fill="currentColor">
              <rect class="bar b1" x="-15" y="-16" width="7" height="32" rx="1.5" />
              <rect class="bar b2" x="-3.5" y="-16" width="7" height="32" rx="1.5" />
              <rect class="bar b3" x="8" y="-16" width="7" height="32" rx="1.5" />
            </g>
          </g>

          <g class="web" fill="none" stroke="currentColor">
            <polygon points="388,190 314,318.2 166,318.2 92,190 166,61.8 314,61.8" />
            <line x1="240" y1="190" x2="388" y2="190" />
            <line x1="240" y1="190" x2="314" y2="318.2" />
            <line x1="240" y1="190" x2="166" y2="318.2" />
            <line x1="240" y1="190" x2="92" y2="190" />
            <line x1="240" y1="190" x2="166" y2="61.8" />
            <line x1="240" y1="190" x2="314" y2="61.8" />
          </g>
          <g class="flow" fill="none" stroke="currentColor" stroke-linecap="round">
            <line x1="240" y1="190" x2="388" y2="190" />
            <line x1="240" y1="190" x2="314" y2="318.2" />
            <line x1="240" y1="190" x2="166" y2="318.2" />
            <line x1="240" y1="190" x2="92" y2="190" />
            <line x1="240" y1="190" x2="166" y2="61.8" />
            <line x1="240" y1="190" x2="314" y2="61.8" />
            <polygon points="388,190 314,318.2 166,318.2 92,190 166,61.8 314,61.8" />
          </g>

          <g fill="currentColor">
            <g class="node n1" transform="translate(388 190)"><circle class="ping" r="13" /><circle r="4.2" /></g>
            <g class="node n2" transform="translate(314 318.2)"><circle class="ping" r="13" /><circle r="4.2" /></g>
            <g class="node n3" transform="translate(166 318.2)"><circle class="ping" r="13" /><circle r="4.2" /></g>
            <g class="node n4" transform="translate(92 190)"><circle class="ping" r="13" /><circle r="4.2" /></g>
            <g class="node n5" transform="translate(166 61.8)"><circle class="ping" r="13" /><circle r="4.2" /></g>
            <g class="node n6" transform="translate(314 61.8)"><circle class="ping" r="13" /><circle r="4.2" /></g>
          </g>
        </svg>
        <div class="welcome">欢迎使用本系统</div>
      </div>
    </section>
    <section class="panel">
      <div class="mobile-title">服务器资源管理</div>
      <a-form class="form" layout="vertical" @submit.prevent="submit">
        <h2>登录</h2>
        <a-form-item>
          <a-input v-model:value="form.username" size="large" placeholder="请输入用户名" autocomplete="username">
            <template #prefix>
              <svg viewBox="0 0 24 24" class="field-icon" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4zm0 2c-3.3 0-8 1.7-8 5v1h16v-1c0-3.3-4.7-5-8-5z"
                />
              </svg>
            </template>
          </a-input>
        </a-form-item>
        <a-form-item>
          <a-input-password
            v-model:value="form.password"
            size="large"
            placeholder="请输入密码"
            autocomplete="current-password"
          >
            <template #prefix>
              <svg viewBox="0 0 24 24" class="field-icon" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M17 9h-1V7a4 4 0 0 0-8 0v2H7a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-8a2 2 0 0 0-2-2zm-6 7.7V18h2v-1.3a1.8 1.8 0 1 0-2 0zM10 9V7a2 2 0 0 1 4 0v2z"
                />
              </svg>
            </template>
          </a-input-password>
        </a-form-item>
        <a-alert v-if="error" type="error" :message="error" show-icon />
        <a-button type="primary" html-type="submit" size="large" block :loading="loading">登录</a-button>
      </a-form>
    </section>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  background: #fff;
}

.banner {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 30px;
  overflow: hidden;
  color: #fff;
  background:
    radial-gradient(circle at 50% 42%, rgba(126, 198, 255, 0.42), transparent 34%),
    linear-gradient(165deg, #0757c8 0%, #0c7cff 52%, #1b90ff 100%);
}

.bg-grid,
.vignette,
.sparks,
.scan {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.bg-grid {
  opacity: 0.9;
}

.mesh {
  opacity: 0.08;
}

.dots {
  opacity: 0.28;
}

.trace {
  opacity: 0.42;
  stroke-dasharray: 7 11;
  animation: dash 2.4s linear infinite;
}

.vignette {
  background:
    radial-gradient(circle at 50% 46%, transparent 30%, rgba(4, 48, 120, 0.16) 100%),
    radial-gradient(circle at 16% 86%, rgba(255, 255, 255, 0.14), transparent 32%),
    radial-gradient(circle at 86% 12%, rgba(255, 255, 255, 0.1), transparent 28%);
}

.spark {
  position: absolute;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #fff;
  opacity: 0.75;
  animation: float 7s ease-in-out infinite;
}

.diamond {
  width: 7px;
  height: 7px;
  border-radius: 1px;
  background: transparent;
  border: 1.5px solid rgba(255, 255, 255, 0.85);
  transform: rotate(45deg);
}

.s1 { top: 18%; left: 14%; animation-delay: 0s; }
.s2 { top: 16%; right: 18%; animation-delay: -1.4s; }
.s3 { top: 62%; left: 10%; animation-delay: -2.6s; }
.s4 { top: 70%; right: 12%; animation-delay: -3.2s; }
.s5 { top: 38%; left: 22%; width: 3px; height: 3px; animation-delay: -4s; }
.s6 { top: 30%; right: 24%; width: 3px; height: 3px; animation-delay: -5s; }
.s7 { top: 78%; left: 28%; animation-delay: -2s; }

.scan {
  inset: auto 0 auto 0;
  height: 90px;
  background: linear-gradient(to bottom, transparent, rgba(255, 255, 255, 0.14), transparent);
  animation: scan 7.5s linear infinite;
}

.banner-title,
.banner-body {
  position: relative;
  z-index: 1;
}

.banner-title,
.mobile-title {
  font-size: 20px;
  font-weight: 700;
}

.banner-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
  text-align: center;
}

.scene {
  width: min(460px, 86%);
  height: auto;
  overflow: visible;
  filter: drop-shadow(0 10px 24px rgba(4, 48, 120, 0.18));
}

.spin,
.radar {
  transform-box: fill-box;
  transform-origin: center;
}

.spin {
  animation: spin 36s linear infinite;
}

.slow {
  animation-duration: 48s;
}

.reverse {
  animation-direction: reverse;
  animation-duration: 28s;
}

.radar {
  animation: spin 8s linear infinite;
}

.halo {
  transform-box: fill-box;
  transform-origin: center;
  animation: breathe 3.6s ease-in-out infinite;
}

.hex {
  fill: rgba(7, 104, 214, 0.72);
  stroke: currentColor;
  stroke-width: 1.6;
}

.hex.inner {
  fill: rgba(255, 255, 255, 0.14);
  stroke-width: 1.2;
}

.bar {
  transform-box: fill-box;
  transform-origin: center bottom;
  animation: eq 1.5s ease-in-out infinite;
}

.b2 { animation-delay: 0.25s; }
.b3 { animation-delay: 0.5s; }

.web {
  opacity: 0.32;
  stroke-width: 1.15;
}

.flow {
  stroke-width: 2;
}

.flow line,
.flow polygon {
  stroke-dasharray: 12 136;
  animation: flow 2.4s linear infinite;
}

.flow line:nth-child(2) { animation-delay: -0.4s; }
.flow line:nth-child(3) { animation-delay: -0.8s; }
.flow line:nth-child(4) { animation-delay: -1.2s; }
.flow line:nth-child(5) { animation-delay: -1.6s; }
.flow line:nth-child(6) { animation-delay: -2s; }

.flow polygon {
  stroke-width: 1.4;
  stroke-dasharray: 18 128;
  animation: dash-ring 5.5s linear infinite;
  opacity: 0.85;
}

.ping {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.2;
  transform-box: fill-box;
  transform-origin: center;
  animation: ping 2.8s ease-out infinite;
}

.n2 .ping { animation-delay: 0.45s; }
.n3 .ping { animation-delay: 0.9s; }
.n4 .ping { animation-delay: 1.35s; }
.n5 .ping { animation-delay: 1.8s; }
.n6 .ping { animation-delay: 2.25s; }

.welcome {
  font-size: 30px;
  letter-spacing: 1px;
  text-shadow: 0 8px 24px rgba(4, 48, 120, 0.25);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes flow {
  to { stroke-dashoffset: -148; }
}

@keyframes dash {
  to { stroke-dashoffset: -180; }
}

@keyframes dash-ring {
  to { stroke-dashoffset: -146; }
}

@keyframes eq {
  0%, 100% { transform: scaleY(0.38); }
  50% { transform: scaleY(1); }
}

@keyframes ping {
  0% { transform: scale(0.65); opacity: 0.85; }
  100% { transform: scale(1.55); opacity: 0; }
}

@keyframes breathe {
  0%, 100% { opacity: 0.55; }
  50% { opacity: 1; }
}

@keyframes float {
  0%, 100% { transform: translateY(0); opacity: 0.35; }
  50% { transform: translateY(-16px); opacity: 0.95; }
}

.diamond.s2,
.diamond.s4,
.diamond.s7 {
  animation-name: float-diamond;
}

@keyframes float-diamond {
  0%, 100% { transform: rotate(45deg) translateY(0); opacity: 0.4; }
  50% { transform: rotate(45deg) translateY(-14px); opacity: 1; }
}

@keyframes scan {
  from { top: -90px; }
  to { top: 110%; }
}

@media (prefers-reduced-motion: reduce) {
  .trace,
  .spark,
  .scan,
  .spin,
  .radar,
  .halo,
  .bar,
  .flow,
  .ping {
    animation: none;
  }
}

.panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 30px;
  background: #fff;
}

.mobile-title {
  display: none;
  margin-bottom: 24px;
  color: #1f1f1f;
}

.form {
  width: min(100%, 460px);
  padding: 20px;
}

h2 {
  margin: 0 0 28px;
  text-align: center;
  font-size: 30px;
  font-weight: 700;
  color: #1f1f1f;
}

.form :deep(.ant-form-item) {
  margin-bottom: 22px;
}

.field-icon {
  width: 16px;
  height: 16px;
  color: #8c8c8c;
}

@media (max-width: 1200px) {
  .banner {
    display: none;
  }

  .panel {
    background: #f5f7f9;
  }

  .mobile-title {
    display: block;
  }

  .form {
    background: #fff;
    border-radius: 24px;
  }
}
</style>
