<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type ResourceStatus } from '../api'

const status = ref<ResourceStatus>()
const error = ref('')

onMounted(async () => {
  try {
    status.value = await api<ResourceStatus>('/resource/status')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法读取控制状态'
  }
})

const reasons = () => status.value?.controlDisabledReasons?.join('；') || '负载控制不可用'
</script>

<template>
  <div class="page">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert
      v-else
      type="warning"
      show-icon
      message="负载控制不可用"
      :description="reasons()"
    />
    <a-tabs>
      <a-tab-pane key="cpu" tab="CPU">
        <a-form layout="vertical">
          <a-form-item label="目标 CPU（1–60%）">
            <a-input-number :value="30" disabled style="width: 180px" />
          </a-form-item>
        </a-form>
      </a-tab-pane>
      <a-tab-pane key="memory" tab="内存">
        <a-form layout="vertical">
          <a-form-item label="模式">
            <a-radio-group value="EXTRA_BYTES" disabled>
              <a-radio value="EXTRA_BYTES">固定增加</a-radio>
              <a-radio value="SYSTEM_PERCENT">系统占用比例</a-radio>
            </a-radio-group>
          </a-form-item>
          <p>页面会同时展示负载持有、JVM 堆使用、进程驻留内存。停止只表示负载引用已释放，系统内存回收由 JVM 和操作系统决定。</p>
        </a-form>
      </a-tab-pane>
      <a-tab-pane key="combined" tab="联合">
        <p>CPU 与内存属于同一次执行。任一引擎启动失败都会回收两项。本阶段没有执行引擎。</p>
      </a-tab-pane>
    </a-tabs>
    <div class="status-row">
      <a-tooltip :title="reasons()"><a-button type="primary" disabled>启动</a-button></a-tooltip>
      <a-tooltip :title="reasons()"><a-button disabled>暂停</a-button></a-tooltip>
      <a-tooltip :title="reasons()"><a-button disabled>恢复</a-button></a-tooltip>
      <a-tooltip :title="reasons()"><a-button disabled>停止</a-button></a-tooltip>
    </div>
    <a-descriptions bordered size="small" :column="1">
      <a-descriptions-item label="控制开关">{{ status?.controlSwitch ?? 'OFF' }}</a-descriptions-item>
      <a-descriptions-item label="急停锁存">
        {{ status?.emergencyLatched ? `已锁存${status.latchReason ? `（${status.latchReason}）` : ''}` : '未锁存' }}
      </a-descriptions-item>
      <a-descriptions-item label="当前会话">
        {{ status?.activeExecution ? status.activeExecution.status : '无活动会话' }}
      </a-descriptions-item>
      <a-descriptions-item label="请求目标 / 实际值 / 允许新增">引擎未安装，没有目标</a-descriptions-item>
    </a-descriptions>
  </div>
</template>
