import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import AppLayout from './views/AppLayout.vue'
import OverviewView from './views/OverviewView.vue'
import ResourceView from './views/ResourceView.vue'
import HistoryView from './views/HistoryView.vue'
import SettingsView from './views/SettingsView.vue'
import PlaceholderView from './views/PlaceholderView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', component: OverviewView },
        { path: 'resource', component: ResourceView },
        { path: 'tasks', component: PlaceholderView, props: { title: '任务模板', phase: 'M4' } },
        { path: 'schedules', component: PlaceholderView, props: { title: '定时计划', phase: 'M4' } },
        { path: 'executions', component: PlaceholderView, props: { title: '执行记录', phase: 'M4' } },
        { path: 'history', component: HistoryView },
        { path: 'settings', component: SettingsView }
      ]
    }
  ]
})

export default router
