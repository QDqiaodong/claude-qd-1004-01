<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import MaintenanceOrderDialog from './MaintenanceOrderDialog.vue'
import {
  moStatusTagType, targetTypeLabel, sourceLabel, diffLevelTagType,
  diffKindLabel, MO_ACTIONS, newRequestId
} from '../../utils/maintenance'

const props = defineProps({ orderId: { type: Number, required: true } })
const emit = defineEmits(['changed'])

const detail = ref(null)
const loading = ref(false)
const actionError = ref('')
const busy = ref(false)

async function load() {
  loading.value = true
  actionError.value = ''
  try {
    detail.value = await api.getMaintenanceOrder(props.orderId)
  } catch (e) {
    actionError.value = e.message
  } finally {
    loading.value = false
  }
}

const order = computed(() => detail.value?.order)
const impacts = computed(() => detail.value?.impacts || [])
const manualImpacts = computed(() => impacts.value.filter(i => i.targetType === 'MANUAL'))
const autoImpacts = computed(() => impacts.value.filter(i => i.targetType !== 'MANUAL'))
const blockDiffs = computed(() => collectDiffs('BLOCK'))
const warnDiffs = computed(() => collectDiffs('WARN'))

function collectDiffs(level) {
  const out = []
  for (const i of impacts.value) {
    for (const d of (i.diffs || [])) {
      if (d.level === level) {
        out.push({ key: `${i.id}-${d.kind}`, code: i.targetCode, type: i.targetType, ...d })
      }
    }
  }
  for (const d of detail.value?.orderLevelDiffs || []) {
    if (d.level === level) {
      out.push({ key: `order-${d.kind}-${d.message}`, code: '检修单', type: null, ...d })
    }
  }
  return out
}

// ---------------- 编辑/重新核对 ----------------
const editVisible = ref(false)
async function onEditDone() {
  editVisible.value = false
  ElMessage.success('草稿已更新')
  await load()
  emit('changed', true)
}
async function recompute() {
  actionError.value = ''
  try {
    await ElMessageBox.confirm(
      '重新核对将丢弃旧的自动关联结果、按当前拓扑重建影响清单（人工边界保留），确认继续？',
      '重新核对拓扑', { type: 'warning' })
  } catch { return }
  busy.value = true
  try {
    detail.value = await api.recomputeMaintenance(props.orderId)
    ElMessage.success('已按当前拓扑重新核对')
    emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

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

async function runStageAction(actionKey) {
  actionError.value = ''
  const meta = MO_ACTIONS[actionKey]
  try {
    await ElMessageBox.confirm(meta.confirm, '操作确认', { type: 'warning' })
  } catch { return }
  const operator = await askOperator()
  if (!operator) return
  busy.value = true
  try {
    // 每次操作固定一个 requestId：并发/重试时后端回放首次结果
    const data = await api.maintenanceAction(props.orderId, actionKey, {
      operator,
      requestId: newRequestId('MT-' + actionKey.toUpperCase())
    })
    ElMessage.success(meta.label + '成功')
    detail.value = await api.getMaintenanceOrder(props.orderId)
    // 后端返回已推进结果时刷新列表计数
    if (data && data.status) emit('changed', true)
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 管段验收 ----------------
const acceptVisible = ref(false)
const acceptRow = ref(null)
const acceptForm = ref({ operator: '', passed: true, note: '' })
function openAccept(row) {
  acceptRow.value = row
  acceptForm.value = { operator: '', passed: true, note: '' }
  acceptVisible.value = true
}
async function submitAccept() {
  actionError.value = ''
  if (!acceptForm.value.operator.trim()) {
    ElMessage.error('验收人不能为空')
    return
  }
  if (!acceptForm.value.passed && !acceptForm.value.note.trim()) {
    ElMessage.error('验收不通过时必须填写原因')
    return
  }
  busy.value = true
  try {
    await api.acceptMaintenanceImpact(props.orderId, acceptRow.value.id, {
      operator: acceptForm.value.operator.trim(),
      passed: acceptForm.value.passed,
      note: acceptForm.value.note.trim() || null,
      requestId: newRequestId('MT-ACCEPT')
    })
    ElMessage.success(acceptForm.value.passed ? '管段验收通过' : '验收不通过已登记')
    acceptVisible.value = false
    await load()
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

// ---------------- 用户安全确认 ----------------
const safetyVisible = ref(false)
const safetyRow = ref(null)
const safetyForm = ref({ operator: '', note: '' })
function openSafety(row) {
  safetyRow.value = row
  safetyForm.value = { operator: '', note: '' }
  safetyVisible.value = true
}
async function submitSafety() {
  actionError.value = ''
  if (!safetyForm.value.operator.trim()) {
    ElMessage.error('确认人不能为空')
    return
  }
  busy.value = true
  try {
    await api.confirmMaintenanceSafety(props.orderId, safetyRow.value.id, {
      operator: safetyForm.value.operator.trim(),
      note: safetyForm.value.note.trim() || null,
      requestId: newRequestId('MT-SAFETY')
    })
    ElMessage.success('用户安全确认完成')
    safetyVisible.value = false
    await load()
  } catch (e) {
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

function acceptanceTagType(s) {
  if (s === '验收通过') return 'success'
  if (s === '验收不通过') return 'danger'
  return 'info'
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="mo-detail">
    <div v-if="actionError" class="err">{{ actionError }}</div>

    <template v-if="order">
      <el-descriptions :column="2" border size="small" class="mo-meta">
        <el-descriptions-item label="检修单编号">{{ order.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="moStatusTagType(order.status)">{{ order.status }}</el-tag>
          <el-tag v-if="order.frozen" type="success" size="small" effect="plain" style="margin-left:6px">
            影响快照已冻结
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="施工负责人">{{ order.ownerName }}</el-descriptions-item>
        <el-descriptions-item label="计划时段">
          {{ order.planStartAt }} ~ {{ order.planEndAt || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="停气">{{ order.cutOffAt || '—' }}
          <template v-if="order.cutOffBy">（{{ order.cutOffBy }}）</template>
        </el-descriptions-item>
        <el-descriptions-item label="复气">{{ order.restoredAt || '—' }}
          <template v-if="order.restoredBy">（{{ order.restoredBy }}）</template>
        </el-descriptions-item>
        <el-descriptions-item label="检修说明" :span="2">{{ order.remark || '—' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 环节操作 -->
      <div class="action-bar">
        <el-button v-if="order.status === '待发布'" size="small" @click="editVisible = true">编辑草稿</el-button>
        <el-button v-if="order.status === '待发布'" size="small" :loading="busy" @click="recompute">
          重新核对拓扑
        </el-button>
        <el-button v-if="order.status === '待发布'" size="small" type="primary" :loading="busy"
          :disabled="blockDiffs.length > 0" @click="runStageAction('publish')">发布并冻结范围</el-button>
        <el-button v-if="order.status === '待停气'" size="small" type="warning" :loading="busy"
          @click="runStageAction('cutoff')">登记停气</el-button>
        <el-button v-if="order.status === '待停气'" size="small" type="primary" :loading="busy"
          :disabled="!order.cutOffAt" @click="runStageAction('start-work')">开工</el-button>
        <el-button v-if="order.status === '施工中'" size="small" type="primary" :loading="busy"
          @click="runStageAction('finish-work')">施工完成</el-button>
        <el-button v-if="order.status === '待验收'" size="small" type="warning" :loading="busy"
          @click="runStageAction('request-restore')">申请复气</el-button>
        <el-button v-if="order.status === '待复气'" size="small" type="success" :loading="busy"
          @click="runStageAction('restore')">复气放行</el-button>
        <span v-if="order.status === '待停气' && !order.cutOffAt" class="action-hint danger-text">
          未停气禁止开工
        </span>
        <span v-if="order.status === '待复气'" class="action-hint">
          待验收管段 <b :class="{ 'danger-text': detail.pendingAcceptanceCount > 0 }">{{ detail.pendingAcceptanceCount }}</b> ·
          待安全确认用户 <b :class="{ 'danger-text': detail.pendingSafetyCount > 0 }">{{ detail.pendingSafetyCount }}</b>
        </span>
      </div>

      <!-- 差异提示：拦截项 / 仅提示项 -->
      <el-alert v-if="blockDiffs.length" :closable="false" type="error" show-icon
        title="存在必须处理的差异，发布将被拒绝（编辑内容已保留）" class="diff-alert">
        <div v-for="d in blockDiffs" :key="d.key" class="diff-line">
          <el-tag size="small" type="danger">{{ d.type ? targetTypeLabel(d.type) : '检修单' }}</el-tag>
          <b>{{ d.code }}</b>
          <el-tag size="small" type="danger" effect="plain">{{ diffKindLabel(d.kind) }}</el-tag>
          <span>{{ d.message }}</span>
        </div>
      </el-alert>
      <el-alert v-if="warnDiffs.length && !blockDiffs.length" :closable="false" type="warning" show-icon
        title="基础档案与冻结快照存在差异（仅提示，不改变已发布范围）" class="diff-alert">
        <div v-for="d in warnDiffs" :key="d.key" class="diff-line">
          <el-tag size="small" type="warning">{{ d.type ? targetTypeLabel(d.type) : '检修单' }}</el-tag>
          <b>{{ d.code }}</b>
          <el-tag size="small" type="warning" effect="plain">{{ diffKindLabel(d.kind) }}</el-tag>
          <span>{{ d.message }}</span>
        </div>
      </el-alert>

      <!-- 影响清单 -->
      <div class="section-title">
        影响清单 · {{ autoImpacts.length }} 个关联对象
        <span class="section-sub">（门站/调压站/燃气用户沿起止点自动关联）</span>
      </div>
      <el-table :data="autoImpacts" border size="small">
        <el-table-column label="类型" width="78">
          <template #default="{ row }">
            <el-tag size="small">{{ targetTypeLabel(row.targetType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetCode" label="编号" width="120" />
        <el-table-column prop="targetName" label="名称/位置" min-width="140" show-overflow-tooltip />
        <el-table-column label="来源" width="78">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'MANUAL' ? 'warning' : 'info'" effect="plain">
              {{ sourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="档案现状" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.present === false" size="small" type="danger">缺失/跨租户</el-tag>
            <el-tag v-else-if="row.currentStatus" size="small"
              :type="row.currentStatus === '停用' || row.currentStatus === '报废' ? 'danger' : 'success'">
              {{ row.currentStatus }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="差异" width="80">
          <template #default="{ row }">
            <el-tag v-if="(row.diffs || []).some(d => d.level === 'BLOCK')"
              size="small" type="danger">{{ row.diffs.length }} 项拦截</el-tag>
            <el-tag v-else-if="(row.diffs || []).length" size="small" type="warning">
              {{ row.diffs.length }} 项提示
            </el-tag>
            <span v-else class="ok-text">一致</span>
          </template>
        </el-table-column>
        <el-table-column label="验收/确认" width="150">
          <template #default="{ row }">
            <template v-if="row.targetType === 'PIPE_SEGMENT'">
              <el-tag size="small" :type="acceptanceTagType(row.acceptanceStatus)">
                {{ row.acceptanceStatus }}
              </el-tag>
            </template>
            <template v-else-if="row.targetType === 'GAS_USER'">
              <el-tag size="small" :type="row.safetyStatus === '已确认' ? 'success' : 'info'">
                {{ row.safetyStatus }}
              </el-tag>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.targetType === 'PIPE_SEGMENT' && order.status === '待验收'"
              size="small" type="primary" link @click="openAccept(row)">验收</el-button>
            <el-button v-else-if="row.targetType === 'GAS_USER' && order.status === '待复气'
                && row.safetyStatus !== '已确认'"
              size="small" type="primary" link @click="openSafety(row)">安全确认</el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 差异明细逐条列出 -->
      <el-row v-for="row in autoImpacts.filter(r => (r.diffs || []).length)" :key="'d-' + row.id"
        class="row-diff-detail">
        <div class="row-diff-head">{{ targetTypeLabel(row.targetType) }} {{ row.targetCode }}</div>
        <div v-for="d in row.diffs" :key="d.kind" class="diff-line">
          <el-tag size="small" :type="diffLevelTagType(d.level)" effect="plain">
            {{ d.level === 'BLOCK' ? '拦截' : '提示' }}
          </el-tag>
          <el-tag size="small" effect="plain">{{ diffKindLabel(d.kind) }}</el-tag>
          <span>{{ d.message }}</span>
        </div>
      </el-row>

      <!-- 人工边界 -->
      <template v-if="manualImpacts.length">
        <div class="section-title">人工边界说明 · {{ manualImpacts.length }} 项</div>
        <el-table :data="manualImpacts" border size="small">
          <el-table-column prop="targetCode" label="编号/标识" width="140" />
          <el-table-column prop="targetName" label="名称/位置" width="180" />
          <el-table-column prop="manualNote" label="边界说明" min-width="200" show-overflow-tooltip />
        </el-table>
      </template>
    </template>

    <!-- 操作人 -->
    <el-dialog v-model="operatorDialog" title="请输入操作人" width="360px"
      :close-on-click-modal="false">
      <el-input v-model="operatorInput" placeholder="如：调度员甲" @keyup.enter="confirmOperator" />
      <template #footer>
        <el-button @click="cancelOperator">取消</el-button>
        <el-button type="primary" @click="confirmOperator">确定</el-button>
      </template>
    </el-dialog>

    <!-- 验收 -->
    <el-dialog v-model="acceptVisible" title="管段验收" width="480px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="验收结论" required>
          <el-radio-group v-model="acceptForm.passed">
            <el-radio :value="true">验收通过</el-radio>
            <el-radio :value="false">验收不通过</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="验收人" required>
          <el-input v-model="acceptForm.operator" placeholder="验收负责人" />
        </el-form-item>
        <el-form-item :label="acceptForm.passed ? '验收意见' : '不通过原因'" :required="!acceptForm.passed">
          <el-input v-model="acceptForm.note" type="textarea" :rows="3"
            :placeholder="acceptForm.passed ? '选填' : '必须说明不通过原因，整改后可重新验收'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitAccept">提交验收</el-button>
      </template>
    </el-dialog>

    <!-- 安全确认 -->
    <el-dialog v-model="safetyVisible" title="受影响用户安全确认" width="480px"
      :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="确认人" required>
          <el-input v-model="safetyForm.operator" placeholder="现场安检/确认人" />
        </el-form-item>
        <el-form-item label="确认说明">
          <el-input v-model="safetyForm.note" type="textarea" :rows="3"
            placeholder="如：入户阀门已关闭、立管保压合格" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="safetyVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitSafety">提交确认</el-button>
      </template>
    </el-dialog>

    <MaintenanceOrderDialog v-if="editVisible && order"
      :order="order" @done="onEditDone" @cancel="editVisible = false" />
  </div>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  flex-wrap: wrap;
}
.action-hint { font-size: 12px; color: var(--el-text-color-secondary); }
.danger-text { color: var(--el-color-danger); }
.ok-text { color: var(--el-color-success); font-size: 12px; }
.section-title {
  font-weight: 600;
  margin: 14px 0 8px;
  font-size: 14px;
}
.section-sub { font-weight: 400; color: var(--el-text-color-secondary); font-size: 12px; }
.diff-alert { margin: 10px 0; }
.diff-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.9;
  flex-wrap: wrap;
}
.row-diff-detail {
  border-left: 3px solid var(--el-color-warning-light-5);
  padding: 4px 10px;
  margin: 4px 0;
}
.row-diff-head { font-weight: 600; font-size: 12px; margin-bottom: 2px; }
</style>
