<script setup>
import { onMounted, ref, watch } from 'vue'
import api from '../api'

const props = defineProps({ tenant: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const levelFilter = ref('')
const materials = ['钢管', 'PE管', '铸铁管']
const levels = ['高压', '次高压', '中压', '低压']
const statuses = ['正常', '待修', '报废']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())
function blank() {
  return {
    segCode: '', startPoint: '', endPoint: '',
    material: '钢管', pressureLevel: '中压',
    lengthM: '', buriedYear: '', status: '正常'
  }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listSegments(
      (statusFilter.value || levelFilter.value)
        ? { status: statusFilter.value, pressureLevel: levelFilter.value }
        : {}
    )
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = { ...form.value, lengthM: Number(form.value.lengthM), buriedYear: form.value.buriedYear ? Number(form.value.buriedYear) : null }
    await api.createSegment(d)
    tip.value = `管段 ${d.segCode} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function patrol(row) {
  error.value = ''; tip.value = ''
  try {
    await api.patrolSegment(row.id)
    tip.value = `${row.segCode} 巡检已登记`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferSegment(row.id, target)
    tip.value = `${row.segCode} 已转为「${target}」`
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
        <span class="panel-title">管网管段 · 巡检与报废</span>
        <span class="panel-desc">长度须 &gt; 0；报废管段不可再登记巡检</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">管段数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">正常</div><div class="v">{{ rows.filter(r => r.status === '正常').length }}</div></div>
        <div class="stat"><div class="k">待修</div><div class="v">{{ rows.filter(r => r.status === '待修').length }}</div></div>
        <div class="stat"><div class="k">报废</div><div class="v">{{ rows.filter(r => r.status === '报废').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select v-model="levelFilter" placeholder="全部压力级制" clearable style="width: 150px" @change="load">
          <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建管段</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="segCode" label="段编码" width="120" />
        <el-table-column prop="startPoint" label="起点" min-width="110" />
        <el-table-column prop="endPoint" label="终点" min-width="110" />
        <el-table-column prop="material" label="材质" width="90" />
        <el-table-column prop="pressureLevel" label="压力级制" width="100" />
        <el-table-column prop="lengthM" label="长度(m)" width="100" />
        <el-table-column prop="buriedYear" label="敷设年" width="90" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '正常' ? 'success' : row.status === '待修' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastPatrolAt" label="上次巡检" width="160" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :disabled="row.status === '报废'" @click="patrol(row)">巡检</el-button>
            <el-button size="small" :disabled="row.status === '待修'" @click="transfer(row, '待修')">报修</el-button>
            <el-button size="small" type="danger" plain :disabled="row.status === '报废'" @click="transfer(row, '报废')">报废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建管段" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="段编码"><el-input v-model="form.segCode" placeholder="如 GD-01" /></el-form-item>
        <el-form-item label="起点"><el-input v-model="form.startPoint" /></el-form-item>
        <el-form-item label="终点"><el-input v-model="form.endPoint" /></el-form-item>
        <el-form-item label="材质">
          <el-select v-model="form.material" style="width: 100%">
            <el-option v-for="m in materials" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="压力级制">
          <el-select v-model="form.pressureLevel" style="width: 100%">
            <el-option v-for="l in levels" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
        <el-form-item label="长度(m)"><el-input v-model="form.lengthM" type="number" step="0.1" /></el-form-item>
        <el-form-item label="敷设年份"><el-input v-model="form.buriedYear" type="number" placeholder="如 2018" /></el-form-item>
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
