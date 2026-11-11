import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import AppLayout from './views/AppLayout.vue'
import OverviewView from './views/OverviewView.vue'
import ResourceView from './views/ResourceView.vue'
import HistoryView from './views/HistoryView.vue'
import SettingsView from './views/SettingsView.vue'
import TasksView from './views/TasksView.vue'
import SchedulesView from './views/SchedulesView.vue'
import ExecutionsView from './views/ExecutionsView.vue'

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
        { path: 'tasks', component: TasksView },
        { path: 'schedules', component: SchedulesView },
        { path: 'executions', component: ExecutionsView },
        { path: 'history', component: HistoryView },
        { path: 'settings', component: SettingsView }
      ]
    }
  ]
})

export default router
