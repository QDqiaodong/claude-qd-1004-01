import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import GateStationView from '../views/GateStationView.vue'
import RegulatorStationView from '../views/RegulatorStationView.vue'
import PipeSegmentView from '../views/PipeSegmentView.vue'
import GasUserView from '../views/GasUserView.vue'
import InspectionView from '../views/InspectionView.vue'
import MaintenanceView from '../views/MaintenanceView.vue'

const routes = [
  { path: '/', redirect: '/gate' },
  { path: '/gate', name: 'gate', component: GateStationView },
  { path: '/regulator', name: 'regulator', component: RegulatorStationView },
  { path: '/pipe', name: 'pipe', component: PipeSegmentView },
  { path: '/user', name: 'user', component: GasUserView },
  { path: '/inspection', name: 'inspection', component: InspectionView },
  { path: '/maintenance', name: 'maintenance', component: MaintenanceView },
  { path: '/:pathMatch(.*)*', redirect: '/gate' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
