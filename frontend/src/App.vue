<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import GateStationView from './views/GateStationView.vue'
import RegulatorStationView from './views/RegulatorStationView.vue'
import PipeSegmentView from './views/PipeSegmentView.vue'
import GasUserView from './views/GasUserView.vue'
import InspectionView from './views/InspectionView.vue'
import MaintenanceView from './views/MaintenanceView.vue'

// ------------------------------------------------------------------
// 导航形态：右侧竖排标签。左侧是业务内容，最右侧一条竖排标签栏切换模块，
// 顶部靠右是「片区」切换（A片区 / B片区）—— 多租户隔离，切换后整页数据随租户变。
// ------------------------------------------------------------------

const viewMap = {
  '/gate': GateStationView,
  '/regulator': RegulatorStationView,
  '/pipe': PipeSegmentView,
  '/user': GasUserView,
  '/inspection': InspectionView,
  '/maintenance': MaintenanceView
}

const tabs = [
  { path: '/gate', label: '门站', sub: '进气 · 加臭 · 输配' },
  { path: '/regulator', label: '调压站', sub: '出口压力 · 检验' },
  { path: '/pipe', label: '管网管段', sub: '材质 · 压力级制 · 巡检' },
  { path: '/user', label: '燃气用户', sub: '抄表 · 入户安检' },
  { path: '/inspection', label: '巡检批次', sub: '批次 · 隐患闭环' },
  { path: '/maintenance', label: '管段检修', sub: '影响评估 · 停复气' }
]

const tenants = ['A片区', 'B片区']
const currentTenant = ref('A片区')
const currentPath = ref(router.currentRoute.value.path)
const counts = ref({ gate: 0, regulator: 0, pipe: 0, user: 0, inspection: 0, maintenance: 0 })

const currentView = computed(() => viewMap[currentPath.value] || GateStationView)

function setTenant(t) {
  if (t === currentTenant.value) return
  currentTenant.value = t
  api.setTenant(t)
  // 切换片区后各视图会随 tenant prop 变化自动重载
}

async function loadCounts() {
  try {
    const [g, r, p, u, b, m] = await Promise.all([
      api.listGateStations(), api.listRegulators(), api.listSegments(), api.listUsers(),
      api.listBatches({ page: 0, size: 1 }),
      api.listMaintenanceOrders({ page: 0, size: 1 })
    ])
    counts.value = {
      gate: g.length, regulator: r.length, pipe: p.length, user: u.length,
      inspection: b.total || 0, maintenance: m.total || 0
    }
  } catch (e) {
    // 计数失败不影响主流程
  }
}

function go(path) {
  router.push(path)
  currentPath.value = path
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
onMounted(async () => {
  api.setTenant(currentTenant.value)
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">燃</span>
        <div class="brand-text">
          <div class="brand-title">城市燃气 · 门站调压与入户安检</div>
          <div class="brand-sub">多租户 · 片区隔离 · 输配全流程</div>
        </div>
      </div>

      <div class="topbar-right">
        <span class="tenant-label">当前片区</span>
        <div class="tenant-switch">
          <button
            v-for="t in tenants"
            :key="t"
            :class="{ on: currentTenant === t }"
            @click="setTenant(t)"
          >{{ t }}</button>
        </div>
      </div>
    </header>

    <div class="body">
      <main class="main">
        <component :is="currentView" :tenant="currentTenant" />
      </main>

      <aside class="right-rail">
        <div class="rail-title">业务模块</div>
        <div
          v-for="t in tabs"
          :key="t.path"
          class="rail-tab"
          :class="{ on: currentPath === t.path }"
          @click="go(t.path)"
        >
          <span class="t-count">{{ counts[t.path.slice(1)] }}</span>
          <div class="t-label">{{ t.label }}</div>
          <div class="t-sub">{{ t.sub }}</div>
        </div>
      </aside>
    </div>
  </div>
</template>
