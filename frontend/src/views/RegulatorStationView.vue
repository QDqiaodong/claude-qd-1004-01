<script setup>
import { onMounted, ref, watch } from 'vue'
import api from '../api'

const props = defineProps({ tenant: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const typeFilter = ref('')
const types = ['区域调压站', '楼栋调压箱', '专用调压站']
const statuses = ['运行', '待检', '停用']

const createDialog = ref(false)
const saving = ref(false)
const form = ref(blank())
function blank() {
  return {
    stationCode: '', stationName: '', stationType: '区域调压站',
    inPressureMpa: '', outletPressureMpa: '', status: '运行'
  }
}

// 出口压力调整：单输入弹窗
const outletDialog = ref(false)
const outletRow = ref(null)
const outletValue = ref('')
const outletBusy = ref(false)

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listRegulators(
      (statusFilter.value || typeFilter.value)
        ? { status: statusFilter.value, stationType: typeFilter.value }
        : {}
    )
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); createDialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      ...form.value,
      inPressureMpa: Number(form.value.inPressureMpa),
      outletPressureMpa: Number(form.value.outletPressureMpa)
    }
    await api.createRegulator(d)
    tip.value = `调压站 ${d.stationName} 已建档`
    createDialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function openOutlet(row) { outletRow.value = row; outletValue.value = row.outletPressureMpa; outletDialog.value = true; error.value = '' }
async function saveOutlet() {
  outletBusy.value = true; error.value = ''
  try {
    await api.setOutlet(outletRow.value.id, Number(outletValue.value))
    tip.value = `${outletRow.value.stationName} 出口压力已调整为 ${outletValue.value} MPa`
    outletDialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { outletBusy.value = false }
}

async function inspect(row) {
  error.value = ''; tip.value = ''
  try {
    await api.inspectRegulator(row.id)
    tip.value = `${row.stationName} 检验已登记`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferRegulator(row.id, target)
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
        <span class="panel-title">调压站 · 出口压力管控</span>
        <span class="panel-desc">出口压力超过 0.40MPa 会被后端拦截；超过 6 个月未检验会提示待检</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">调压站数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ rows.filter(r => r.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">待检</div><div class="v">{{ rows.filter(r => r.status === '待检').length }}</div></div>
        <div class="stat"><div class="k">停用</div><div class="v">{{ rows.filter(r => r.status === '停用').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 150px" @change="load">
          <el-option v-for="t in types" :key="t" :label="t" :value="t" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建调压站</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="stationCode" label="站编码" width="120" />
        <el-table-column prop="stationName" label="站名" min-width="130" />
        <el-table-column prop="stationType" label="类型" width="120" />
        <el-table-column prop="inPressureMpa" label="进口(MPa)" width="100" />
        <el-table-column prop="outletPressureMpa" label="出口(MPa)" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '运行' ? 'success' : row.status === '待检' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastInspectAt" label="上次检验" width="160" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openOutlet(row)">调出口</el-button>
            <el-button size="small" @click="inspect(row)">登记检验</el-button>
            <el-button size="small" type="danger" plain :disabled="row.status === '停用'" @click="transfer(row, '停用')">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createDialog" title="新建调压站" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="站编码"><el-input v-model="form.stationCode" placeholder="如 TZ-01" /></el-form-item>
        <el-form-item label="站名"><el-input v-model="form.stationName" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.stationType" style="width: 100%">
            <el-option v-for="t in types" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="进口压力(MPa)"><el-input v-model="form.inPressureMpa" type="number" step="0.01" /></el-form-item>
        <el-form-item label="出口压力(MPa)"><el-input v-model="form.outletPressureMpa" type="number" step="0.01" /></el-form-item>
        <el-form-item label="状态" class="full">
          <el-radio-group v-model="form.status">
            <el-radio v-for="s in statuses" :key="s" :value="s">{{ s }}</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="outletDialog" title="调整出口压力" width="420px">
      <p style="margin:0 0 10px;color:var(--muted);font-size:13px">
        {{ outletRow && outletRow.stationName }} —— 出口压力上限 0.40MPa，超限将被拦截
      </p>
      <el-input v-model="outletValue" type="number" step="0.01" placeholder="出口压力 MPa">
        <template #append>MPa</template>
      </el-input>
      <template #footer>
        <el-button @click="outletDialog = false">取消</el-button>
        <el-button type="primary" :loading="outletBusy" @click="saveOutlet">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
