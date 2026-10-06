<script setup>
import { onMounted, ref, watch } from 'vue'
import api from '../api'

const props = defineProps({ tenant: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const dialog = ref(false)
const saving = ref(false)
const statuses = ['运行', '检修', '停用']

const form = ref(blank())
function blank() {
  return {
    stationCode: '', stationName: '',
    inletPressureMpa: '', outletPressureMpa: '',
    dailyFlowM3: '', odorantMg: '', status: '运行'
  }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listGateStations(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      ...form.value,
      inletPressureMpa: Number(form.value.inletPressureMpa),
      outletPressureMpa: Number(form.value.outletPressureMpa),
      dailyFlowM3: Number(form.value.dailyFlowM3),
      odorantMg: Number(form.value.odorantMg)
    }
    await api.createGateStation(d)
    tip.value = `门站 ${d.stationName} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferGateStation(row.id, target)
    tip.value = `${row.stationName} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

watch(() => props.tenant, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">门站 · 进气输配</span>
        <span class="panel-desc">进站压力需 ≥ 0.80MPa、加臭剂单耗需 ≥ 20mg/m³ 才能投运</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">门站数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ rows.filter(r => r.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">检修</div><div class="v">{{ rows.filter(r => r.status === '检修').length }}</div></div>
        <div class="stat"><div class="k">停用</div><div class="v">{{ rows.filter(r => r.status === '停用').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建门站</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="stationCode" label="站编码" width="120" />
        <el-table-column prop="stationName" label="站名" min-width="140" />
        <el-table-column prop="inletPressureMpa" label="进站(MPa)" width="110" />
        <el-table-column prop="outletPressureMpa" label="出站(MPa)" width="110" />
        <el-table-column prop="dailyFlowM3" label="日输气(m³)" width="120" />
        <el-table-column prop="odorantMg" label="加臭(mg/m³)" width="110" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '运行' ? 'success' : row.status === '检修' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :disabled="row.status === '运行'" @click="transfer(row, '运行')">投运</el-button>
            <el-button size="small" :disabled="row.status === '检修'" @click="transfer(row, '检修')">检修</el-button>
            <el-button size="small" type="danger" plain :disabled="row.status === '停用'" @click="transfer(row, '停用')">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建门站" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="站编码"><el-input v-model="form.stationCode" placeholder="如 MZ-01" /></el-form-item>
        <el-form-item label="站名"><el-input v-model="form.stationName" /></el-form-item>
        <el-form-item label="进站压力(MPa)"><el-input v-model="form.inletPressureMpa" type="number" step="0.01" /></el-form-item>
        <el-form-item label="出站压力(MPa)"><el-input v-model="form.outletPressureMpa" type="number" step="0.01" /></el-form-item>
        <el-form-item label="日输气量(m³)"><el-input v-model="form.dailyFlowM3" type="number" step="0.01" /></el-form-item>
        <el-form-item label="加臭剂(mg/m³)"><el-input v-model="form.odorantMg" type="number" step="0.1" /></el-form-item>
        <el-form-item label="状态" class="full">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>
