<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import {
  maintStatusTagType, impactTypeLabel, impactSourceLabel,
  confirmStatusTagType, acceptStatusTagType, diffTypeLabel, diffTagType
} from '../../utils/maintenance'
import { newRequestId } from '../../utils/inspection'

const props = defineProps({ orderId: { type: Number, required: true } })
const emit = defineEmits(['changed', 'edit'])

const detail = ref(null)
const loading = ref(false)
const actionError = ref('')
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    detail.value = await api.getOrder(props.orderId)
  } catch (e) {
    actionError.value = e.message
  } finally {
    loading.value = false
  }
}

const order = computed(() => detail.value?.order)
const segments = computed(() => detail.value?.segments || [])
const impacts = computed(() => detail.value?.impacts || [])
const diffs = computed(() => detail.value?.diffs || [])
const pendingAcceptCount = computed(() => detail.value?.pendingAcceptCount ?? 0)
const pendingConfirmCount = computed(() => detail.value?.pendingConfirmCount ?? 0)
const isDraft = computed(() => order.value?.status === '待发布')

// ---------------- 操作人输入 ----------------
const operatorDialog = ref(false)
const operatorInput = ref('')
let operatorResolve = null
function askOperator() {
  return new Promise((resolve) => {
    operatorInput.value = ''
    operatorDialog.value = true
    operatorResolve = resolve
  })
}
function confirmOperator() {
  const v = operatorInput.value.trim()
  operatorDialog.value = false
  if (operatorResolve) operatorResolve(v || null)
}
function cancelOperator() {
  operatorDialog.value = false
  if (operatorResolve) operatorResolve(null)
}

// ---------------- 状态推进（发布/停气/完工/复气） ----------------
// 每个动作一个 requestId：失败后重试复用同一 requestId，后端幂等回放首次结果；
// 动作成功后清掉，下一次推进生成新的。
const pendingRequestIds = reactive({})
function requestIdFor(action) {
  if (!pendingRequestIds[action]) {
    pendingRequestIds[action] = newRequestId('MAINT')
  }
  return pendingRequestIds[action]
}

async function runAction(action, confirmText, successText) {
  actionError.value = ''
  try {
    await ElMessageBox.confirm(confirmText, '操作确认', { type: 'warning' })
  } catch {
    return
  }
  const operator = await askOperator()
  if (!operator) return
  busy.value = true
  try {
    await api.orderAction(props.orderId, action, {
      operator,
      version: order.value.version,
      requestId: requestIdFor(action)
    })
    delete pendingRequestIds[action]
    ElMessage.success(successText)
    await load()
    emit('changed', true)
  } catch (e) {
    // 拓扑冲突 / 版本冲突 / 双闸门拦截 / 并发 409：明确提示，不覆盖页面状态
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

const publish = () => runAction('publish',
  '发布后将冻结检修影响快照，基础档案变化只提示差异、不再改写范围。确认发布？', '检修单已发布，影响快照已冻结')
const stopGas = () => runAction('stop-gas',
  '确认已按计划停气？停气后才可开工，停气后进入施工中。', '已停气，检修开工')
const finishWork = () => runAction('finish-work',
  '确认现场施工已完工？完工后进入待验收。', '施工已完工，进入验收环节')
const restoreGas = () => runAction('restore-gas',
  `复气放行要求全部管段验收通过且受影响用户安全确认完成（当前未验收 ${pendingAcceptCount.value} 段、未确认 ${pendingConfirmCount.value} 户）。确认复气？`,
  '复气放行，检修单已完成')

// ---------------- 重新评估影响（待发布） ----------------
async function refreshImpact() {
  actionError.value = ''
  try {
    await ElMessageBox.confirm('将按当前拓扑重建自动关联的影响清单（人工边界保留）。确认重新评估？',
      '操作确认', { type: 'warning' })
  } catch {
    return
  }
  busy.value = true
  try {
    await api.refreshImpact(props.orderId, order.value.version)
    ElMessage.success('已按当前拓扑重新评估影响')
    await load()
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 人工边界对象 ----------------
const boundaryForm = reactive({ objectType: 'GAS_USER', objectId: null, note: '' })
const boundaryCandidates = reactive({ GATE_STATION: [], REGULATOR_STATION: [], GAS_USER: [] })
const boundaryBusy = ref(false)

async function loadBoundaryCandidates() {
  try {
    const [gates, regulators, users] = await Promise.all([
      api.listGateStations(), api.listRegulators(), api.listUsers()
    ])
    boundaryCandidates.GATE_STATION = gates || []
    boundaryCandidates.REGULATOR_STATION = regulators || []
    boundaryCandidates.GAS_USER = users || []
  } catch (e) {
    // 候选加载失败只提示，不影响详情展示
    actionError.value = e.message
  }
}

function candidateLabel(o) {
  if (boundaryForm.objectType === 'GAS_USER') return `${o.userNo} ${o.userName}（${o.address}）`
  return `${o.stationCode} ${o.stationName}`
}

async function submitBoundary() {
  actionError.value = ''
  if (!boundaryForm.objectId) {
    actionError.value = '请选择要补充的边界对象'
    return
  }
  if (!boundaryForm.note.trim()) {
    actionError.value = '人工说明不能为空：无法自动判定的边界对象必须填写说明'
    return
  }
  boundaryBusy.value = true
  try {
    await api.addBoundary(props.orderId, {
      objectType: boundaryForm.objectType,
      objectId: boundaryForm.objectId,
      note: boundaryForm.note.trim(),
      version: order.value.version
    })
    ElMessage.success('人工边界对象已补充')
    boundaryForm.objectId = null
    boundaryForm.note = ''
    await load()
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    boundaryBusy.value = false
  }
}

async function removeBoundary(row) {
  actionError.value = ''
  try {
    await ElMessageBox.confirm(`确认移除人工边界对象「${row.objectName}」？`, '操作确认', { type: 'warning' })
  } catch {
    return
  }
  busy.value = true
  try {
    await api.removeBoundary(props.orderId, row.id, order.value.version)
    ElMessage.success('人工边界对象已移除')
    await load()
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 管段验收 ----------------
const acceptDialog = ref(false)
const acceptTarget = ref(null)
const acceptNote = ref('')
const acceptOperator = ref('')
const acceptError = ref('')
const acceptBusy = ref(false)

function openAccept(row) {
  acceptTarget.value = row
  acceptNote.value = ''
  acceptOperator.value = ''
  acceptError.value = ''
  acceptDialog.value = true
}

async function submitAccept() {
  acceptError.value = ''
  if (!acceptOperator.value.trim()) {
    acceptError.value = '验收人不能为空'
    return
  }
  acceptBusy.value = true
  try {
    await api.acceptSegment(props.orderId, acceptTarget.value.id, {
      operator: acceptOperator.value.trim(),
      note: acceptNote.value.trim(),
      version: order.value.version
    })
    ElMessage.success(`管段 ${acceptTarget.value.segCode} 验收通过`)
    acceptDialog.value = false
    await load()
    emit('changed', true)
  } catch (e) {
    acceptError.value = e.message
  } finally {
    acceptBusy.value = false
  }
}

// ---------------- 用户安全确认 ----------------
async function confirmUser(row) {
  actionError.value = ''
  const operator = await askOperator()
  if (!operator) return
  busy.value = true
  try {
    await api.confirmUser(props.orderId, row.id, { operator })
    ElMessage.success(`用户 ${row.objectCode} 安全确认完成`)
    await load()
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  load()
  loadBoundaryCandidates()
})
</script>

<template>
  <div v-loading="loading" class="batch-detail maint-detail">
    <div v-if="actionError" class="err">{{ actionError }}</div>

    <template v-if="order">
      <el-descriptions :column="2" border size="small" class="batch-meta">
        <el-descriptions-item label="检修单号">{{ order.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="maintStatusTagType(order.status)">{{ order.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="施工负责人">{{ order.leaderName }}</el-descriptions-item>
        <el-descriptions-item label="版本">v{{ order.version }}</el-descriptions-item>
        <el-descriptions-item label="计划时段" :span="2">
          {{ order.planStartAt }}<template v-if="order.planEndAt"> ~ {{ order.planEndAt }}</template>
        </el-descriptions-item>
        <el-descriptions-item label="发布" :span="2">
          {{ order.publishedBy || '—' }}<template v-if="order.publishedAt"> · {{ order.publishedAt }}</template>
        </el-descriptions-item>
        <el-descriptions-item label="停气">
          {{ order.stoppedBy || '—' }}<template v-if="order.stoppedAt"> · {{ order.stoppedAt }}</template>
        </el-descriptions-item>
        <el-descriptions-item label="复气">
          {{ order.restoredBy || '—' }}<template v-if="order.restoredAt"> · {{ order.restoredAt }}</template>
        </el-descriptions-item>
        <el-descriptions-item label="检修范围说明" :span="2">{{ order.scopeRemark }}</el-descriptions-item>
      </el-descriptions>

      <div class="action-bar">
        <template v-if="isDraft">
          <el-button type="primary" :loading="busy" @click="publish">发布（冻结影响快照）</el-button>
          <el-button :loading="busy" @click="refreshImpact">重新评估影响</el-button>
          <el-button :loading="busy" @click="emit('edit', order)">编辑草稿</el-button>
        </template>
        <el-button v-if="order.status === '待停气'" type="warning" :loading="busy" @click="stopGas">
          停气开工
        </el-button>
        <el-button v-if="order.status === '施工中'" type="warning" :loading="busy" @click="finishWork">
          完工报验
        </el-button>
        <el-button v-if="order.status === '待复气'" type="success" :loading="busy" @click="restoreGas">
          复气放行
        </el-button>
        <span class="action-hint">
          未验收管段 <b :class="{ 'danger-text': pendingAcceptCount > 0 }">{{ pendingAcceptCount }}</b> 段 ·
          未确认用户 <b :class="{ 'danger-text': pendingConfirmCount > 0 }">{{ pendingConfirmCount }}</b> 户 ·
          复气放行需两项同时归零
        </span>
      </div>

      <!-- 影响差异提示：发布后快照与当前拓扑/档案的偏差，只提示不改写 -->
      <template v-if="diffs.length > 0">
        <div class="section-title">影响差异提示（快照已冻结，以下变化未纳入已发布范围）</div>
        <el-alert
          v-for="(d, i) in diffs" :key="i"
          class="diff-alert"
          :type="d.diffType === 'ADDED' ? 'warning' : 'error'"
          :closable="false"
          show-icon
        >
          <template #title>
            <el-tag :type="diffTagType(d.diffType)" size="small" class="diff-tag">{{ diffTypeLabel(d.diffType) }}</el-tag>
            {{ impactTypeLabel(d.objectType) }} {{ d.objectCode }} {{ d.objectName }} — {{ d.message }}
          </template>
        </el-alert>
      </template>

      <div class="section-title">检修范围管段 · 逐段验收</div>
      <el-table :data="segments" border size="small">
        <el-table-column prop="segCode" label="管段编号" width="120" />
        <el-table-column prop="segName" label="起止点" min-width="180" />
        <el-table-column label="验收状态" width="90">
          <template #default="{ row }">
            <el-tag :type="acceptStatusTagType(row.acceptStatus)" size="small">{{ row.acceptStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="acceptedBy" label="验收人" width="90">
          <template #default="{ row }">{{ row.acceptedBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="acceptNote" label="验收意见" min-width="140">
          <template #default="{ row }">{{ row.acceptNote || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" link
              :disabled="!(order.status === '待验收' && row.acceptStatus === '待验收')"
              @click="openAccept(row)">验收通过</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="section-title">影响清单（{{ impacts.length }}）· 发布即冻结</div>
      <el-table :data="impacts" border size="small">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ impactTypeLabel(row.objectType) }}</template>
        </el-table-column>
        <el-table-column prop="objectCode" label="编号" width="100" />
        <el-table-column prop="objectName" label="名称" min-width="110" />
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag :type="row.source === 'MANUAL' ? 'warning' : 'info'" size="small">
              {{ impactSourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联来源 / 人工说明" min-width="180">
          <template #default="{ row }">
            <div>{{ row.viaDesc || '—' }}</div>
            <div v-if="row.manualNote" class="manual-note">{{ row.manualNote }}</div>
          </template>
        </el-table-column>
        <el-table-column label="安全确认" width="90">
          <template #default="{ row }">
            <el-tag :type="confirmStatusTagType(row.confirmStatus)" size="small">{{ row.confirmStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.objectType === 'GAS_USER'" size="small" type="success" link
              :disabled="!(row.confirmStatus === '待确认' && order.status !== '待发布' && order.status !== '已完成')"
              @click="confirmUser(row)">安全确认</el-button>
            <el-button v-if="row.source === 'MANUAL'" size="small" type="danger" link
              :disabled="!isDraft"
              @click="removeBoundary(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 人工边界补充：仅待发布可编辑 -->
      <template v-if="isDraft">
        <div class="section-title">补充人工边界对象（自动拓扑无法判定时使用，必填人工说明）</div>
        <div class="boundary-form">
          <el-select v-model="boundaryForm.objectType" style="width: 120px" @change="boundaryForm.objectId = null">
            <el-option label="门站" value="GATE_STATION" />
            <el-option label="调压站" value="REGULATOR_STATION" />
            <el-option label="燃气用户" value="GAS_USER" />
          </el-select>
          <el-select v-model="boundaryForm.objectId" filterable placeholder="选择对象" style="width: 260px">
            <el-option
              v-for="o in boundaryCandidates[boundaryForm.objectType]" :key="o.id"
              :label="candidateLabel(o)" :value="o.id"
            />
          </el-select>
          <el-input
            v-model="boundaryForm.note"
            placeholder="人工说明：无法自动判定的边界理由（必填）"
            style="width: 300px"
          />
          <el-button type="primary" plain :loading="boundaryBusy" @click="submitBoundary">补充</el-button>
        </div>
      </template>
    </template>

    <el-dialog v-model="operatorDialog" title="请输入操作人" width="360px"
               class="operator-dialog" :close-on-click-modal="false">
      <el-input v-model="operatorInput" placeholder="如：调度甲" @keyup.enter="confirmOperator" />
      <template #footer>
        <el-button @click="cancelOperator">取消</el-button>
        <el-button type="primary" @click="confirmOperator">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="acceptDialog" title="管段验收" width="420px"
               class="accept-dialog" :close-on-click-modal="false">
      <div v-if="acceptError" class="err">{{ acceptError }}</div>
      <el-form label-width="90px" class="dialog-form">
        <el-form-item label="管段">
          <span v-if="acceptTarget">{{ acceptTarget.segCode }}（{{ acceptTarget.segName }}）</span>
        </el-form-item>
        <el-form-item label="验收人" required>
          <el-input v-model="acceptOperator" placeholder="如：验收员赵" />
        </el-form-item>
        <el-form-item label="验收意见">
          <el-input v-model="acceptNote" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptDialog = false">取消</el-button>
        <el-button type="primary" :loading="acceptBusy" @click="submitAccept">验收通过</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.diff-alert {
  margin-bottom: 8px;
}
.diff-tag {
  margin-right: 6px;
}
.manual-note {
  color: var(--muted);
  font-size: 12px;
}
.boundary-form {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
