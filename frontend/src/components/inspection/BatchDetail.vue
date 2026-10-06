<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import {
  batchStatusTagType, hazardStatusTagType, riskTagType, targetTypeLabel
} from '../../utils/inspection'
import InspectDialog from './InspectDialog.vue'
import HazardDialog from './HazardDialog.vue'

const props = defineProps({ batchId: { type: Number, required: true } })
const emit = defineEmits(['changed'])

const detail = ref(null)
const loading = ref(false)
const actionError = ref('')
const busy = ref(false)
const activeHazards = ref([])

async function load() {
  loading.value = true
  try {
    detail.value = await api.getBatch(props.batchId)
  } catch (e) {
    actionError.value = e.message
  } finally {
    loading.value = false
  }
}

const batch = computed(() => detail.value?.batch)
const targets = computed(() => detail.value?.targets || [])
const hazards = computed(() => detail.value?.hazards || [])
const openHazardCount = computed(() => detail.value?.openHazardCount ?? 0)

function findHazard(target) {
  return hazards.value.find(h =>
    h.targetType === target.targetType && h.targetId === target.targetId)
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
    await api.batchAction(props.batchId, action, operator)
    ElMessage.success(successText)
    await load()
    emit('changed', true)
  } catch (e) {
    // 非法跳转 / 过期 / 并发冲突：明确提示，不覆盖页面状态
    actionError.value = e.message
  } finally {
    busy.value = false
  }
}

const startBatch = () => runAction('start', '确认开始执行该批次？过期批次也必须先补执行，不能跳过执行。', '批次已开始执行')
const finishBatch = () => runAction('finish', '确认所有对象已完成现场巡检？有隐患批次将进入待整改。', '已进入待整改')
const requestReview = () => runAction('request-review', '确认申请复核？无隐患可直接申请；有隐患需全部提交整改。', '已申请复核')
const closeBatch = () => runAction('close', '关闭前将在事务内再次确认全部隐患已通过，确认关闭？', '批次已关闭')

// ---------------- 对象巡检登记 ----------------
const inspectVisible = ref(false)
const inspectTargetRow = ref(null)
function openInspect(row) {
  inspectTargetRow.value = row
  inspectVisible.value = true
}
async function onInspected() {
  inspectVisible.value = false
  await load()
  emit('changed', true)
}

// ---------------- 隐患整改/复核 ----------------
const hazardVisible = ref(false)
const hazardMode = ref('rectify')
const currentHazard = ref(null)
function openRectify(h) {
  currentHazard.value = h
  hazardMode.value = 'rectify'
  hazardVisible.value = true
}
function openReview(h) {
  currentHazard.value = h
  hazardMode.value = 'review'
  hazardVisible.value = true
}
async function onHazardChanged() {
  hazardVisible.value = false
  await load()
  emit('changed', true)
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="batch-detail">
    <div v-if="actionError" class="err">{{ actionError }}</div>

    <template v-if="batch">
      <el-descriptions :column="2" border size="small" class="batch-meta">
        <el-descriptions-item label="批次编号">{{ batch.batchNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="batchStatusTagType(batch.status)">{{ batch.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="负责人">{{ batch.ownerName }}</el-descriptions-item>
        <el-descriptions-item label="计划开始">{{ batch.planStartAt }}</el-descriptions-item>
        <el-descriptions-item label="计划结束">{{ batch.planEndAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="实际开始">{{ batch.startedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="关闭人/时间" :span="2">
          {{ batch.closedBy || '—' }}<template v-if="batch.closedAt"> · {{ batch.closedAt }}</template>
        </el-descriptions-item>
        <el-descriptions-item label="巡检说明" :span="2">{{ batch.remark }}</el-descriptions-item>
      </el-descriptions>

      <div class="action-bar">
        <el-button type="primary" :loading="busy"
          :disabled="batch.status !== '待执行'" @click="startBatch">开始执行</el-button>
        <el-button type="warning" :loading="busy"
          :disabled="!(batch.status === '执行中' && openHazardCount > 0)"
          @click="finishBatch">完成现场执行</el-button>
        <el-button type="warning" :loading="busy"
          :disabled="!(batch.status === '执行中' || batch.status === '待整改')"
          @click="requestReview">申请复核</el-button>
        <el-button type="success" :loading="busy"
          :disabled="batch.status !== '待复核'" @click="closeBatch">复核收口 / 关闭</el-button>
        <span class="action-hint">
          未闭环隐患 <b :class="{ 'danger-text': openHazardCount > 0 }">{{ openHazardCount }}</b> 项 ·
          无隐患可申请复核，有未通过隐患不得关闭
        </span>
      </div>

      <div class="section-title">关联对象 · 最新巡检结果</div>
      <el-table :data="targets" border size="small">
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ targetTypeLabel(row.targetType) }}</template>
        </el-table-column>
        <el-table-column prop="targetCode" label="编号" width="130" />
        <el-table-column prop="targetName" label="名称" min-width="150" />
        <el-table-column prop="roundNo" label="轮次" width="60" />
        <el-table-column label="最新结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.latestResult === '隐患' ? 'danger' : row.latestResult === '正常' ? 'success' : 'info'"
                    size="small">{{ row.latestResult }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="checkedAt" label="巡检时间" width="170" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" link
              :disabled="batch.status !== '执行中' || row.latestResult !== '待检'"
              @click="openInspect(row)">登记结果</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="section-title">隐患清单 · 处理历史</div>
      <el-empty v-if="hazards.length === 0" description="本批次无隐患" :image-size="60" />
      <el-collapse v-else v-model="activeHazards">
        <el-collapse-item v-for="h in hazards" :key="h.id" :name="h.id">
          <template #title>
            <div class="hazard-title">
              <el-tag :type="riskTagType(h.riskLevel)" size="small">{{ h.riskLevel }}风险</el-tag>
              <el-tag :type="hazardStatusTagType(h.status)" size="small" style="margin-left: 6px">{{ h.status }}</el-tag>
              <span class="hazard-target">{{ targetTypeLabel(h.targetType) }} #{{ h.targetId }} · 第{{ h.roundNo }}轮</span>
              <span class="hazard-problem">{{ h.problem }}</span>
            </div>
          </template>

          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="整改要求" :span="2">{{ h.rectifyRequire }}</el-descriptions-item>
            <el-descriptions-item label="责任人">{{ h.ownerName }}</el-descriptions-item>
            <el-descriptions-item label="整改期限">
              <span :class="{ 'danger-text': h.status !== '已通过' && new Date(h.dueAt) < new Date() }">{{ h.dueAt }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="最近整改" :span="2">{{ h.rectifyNote || '—' }}</el-descriptions-item>
            <el-descriptions-item label="复核人">{{ h.reviewedBy || '—' }}</el-descriptions-item>
            <el-descriptions-item label="复核时间">{{ h.reviewedAt || '—' }}</el-descriptions-item>
          </el-descriptions>

          <el-timeline class="history-line">
            <el-timeline-item v-for="his in h.history" :key="his.id"
              :timestamp="his.createdAt" placement="top"
              :type="his.action === '驳回' ? 'danger' : his.action === '复核通过' ? 'success' : 'primary'">
              <b>{{ his.action }}</b> · {{ his.operatorName }}
              <div class="history-note">{{ his.note }}</div>
            </el-timeline-item>
          </el-timeline>

          <div class="hazard-actions">
            <el-button size="small" type="warning"
              :disabled="!(batch.status === '待整改' && h.status === '整改中')"
              @click="openRectify(h)">提交整改</el-button>
            <el-button size="small" type="primary"
              :disabled="!(batch.status === '待复核' && h.status === '待复核')"
              @click="openReview(h)">隐患复核</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>

    <el-dialog v-model="operatorDialog" title="请输入操作人" width="360px" :close-on-click-modal="false">
      <el-input v-model="operatorInput" placeholder="如：巡检员甲" @keyup.enter="confirmOperator" />
      <template #footer>
        <el-button @click="cancelOperator">取消</el-button>
        <el-button type="primary" @click="confirmOperator">确定</el-button>
      </template>
    </el-dialog>

    <InspectDialog v-if="inspectVisible"
      :batch-id="props.batchId" :target="inspectTargetRow"
      @done="onInspected" @cancel="inspectVisible = false" />

    <HazardDialog v-if="hazardVisible && currentHazard"
      :mode="hazardMode" :hazard="currentHazard"
      @done="onHazardChanged" @cancel="hazardVisible = false" />
  </div>
</template>
