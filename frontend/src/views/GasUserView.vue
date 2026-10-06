<script setup>
import { onMounted, ref, watch } from 'vue'
import api from '../api'

const props = defineProps({ tenant: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const statusFilter = ref('')
const statuses = ['正常', '欠费', '停用']
const checkResults = ['合格', '限期整改', '隐患停用']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())
function blank() {
  return {
    userNo: '', userName: '', address: '',
    meterNo: '', lastReading: '0', currentReading: '0', status: '正常'
  }
}

// 抄表：单输入弹窗
const meterDialog = ref(false)
const meterRow = ref(null)
const meterValue = ref('')
const meterBusy = ref(false)

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listUsers(statusFilter.value ? { status: statusFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }
async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      ...form.value,
      lastReading: Number(form.value.lastReading),
      currentReading: Number(form.value.currentReading)
    }
    await api.createUser(d)
    tip.value = `用户 ${d.userName} 已建档`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function openMeter(row) { meterRow.value = row; meterValue.value = row.currentReading; meterDialog.value = true; error.value = '' }
async function saveMeter() {
  meterBusy.value = true; error.value = ''
  try {
    await api.readMeter(meterRow.value.id, Number(meterValue.value))
    tip.value = `${meterRow.value.userName} 抄表已登记，读数 ${meterValue.value}`
    meterDialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { meterBusy.value = false }
}

async function check(row, result) {
  error.value = ''; tip.value = ''
  try {
    await api.checkUser(row.id, result)
    tip.value = `${row.userName} 入户安检结果：${result}`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(row, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferUser(row.id, target)
    tip.value = `${row.userName} 已转为「${target}」`
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
        <span class="panel-title">燃气用户 · 抄表与入户安检</span>
        <span class="panel-desc">读数不可倒走、单次用量不可超 2000m³；隐患停用的用户直接停用</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">用户数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">正常</div><div class="v">{{ rows.filter(r => r.status === '正常').length }}</div></div>
        <div class="stat"><div class="k">欠费</div><div class="v">{{ rows.filter(r => r.status === '欠费').length }}</div></div>
        <div class="stat"><div class="k">停用</div><div class="v">{{ rows.filter(r => r.status === '停用').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建用户</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="userNo" label="用户编号" width="120" />
        <el-table-column prop="userName" label="姓名" width="100" />
        <el-table-column prop="address" label="地址" min-width="150" show-overflow-tooltip />
        <el-table-column prop="meterNo" label="表号" width="120" />
        <el-table-column prop="lastReading" label="上次读数" width="100" />
        <el-table-column prop="currentReading" label="本次读数" width="100" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === '正常' ? 'success' : row.status === '欠费' ? 'warning' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="checkResult" label="安检结果" width="100">
          <template #default="{ row }">
            <span v-if="!row.checkResult">—</span>
            <el-tag v-else :type="row.checkResult === '合格' ? 'success' : 'danger'">{{ row.checkResult }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastCheckAt" label="上次安检" width="160" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openMeter(row)">抄表</el-button>
            <el-dropdown trigger="click" @command="(r) => check(row, r)">
              <el-button size="small">安检<el-icon><arrow-down /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="r in checkResults" :key="r" :command="r">{{ r }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button size="small" type="danger" plain :disabled="row.status === '停用'" @click="transfer(row, '停用')">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建燃气用户" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="用户编号"><el-input v-model="form.userNo" placeholder="如 U-0001" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.userName" /></el-form-item>
        <el-form-item label="地址" class="full"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="表号"><el-input v-model="form.meterNo" /></el-form-item>
        <el-form-item label="上次读数"><el-input v-model="form.lastReading" type="number" step="0.01" /></el-form-item>
        <el-form-item label="本次读数"><el-input v-model="form.currentReading" type="number" step="0.01" /></el-form-item>
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

    <el-dialog v-model="meterDialog" title="抄表登记" width="420px">
      <p style="margin:0 0 10px;color:var(--muted);font-size:13px">
        {{ meterRow && meterRow.userName }} —— 读数不可低于上次（{{ meterRow && meterRow.lastReading }}），单次用量不可超 2000m³
      </p>
      <el-input v-model="meterValue" type="number" step="0.01" placeholder="本次读数">
        <template #append>m³</template>
      </el-input>
      <template #footer>
        <el-button @click="meterDialog = false">取消</el-button>
        <el-button type="primary" :loading="meterBusy" @click="saveMeter">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>
