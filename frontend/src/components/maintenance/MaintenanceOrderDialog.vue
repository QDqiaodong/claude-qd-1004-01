<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

// 检修单创建 / 草稿编辑对话框：
// 检修范围只选管段；门站/调压站/燃气用户由后端沿起止点自动关联，
// 运营人员只需补充「无法自动判定的边界对象」说明。提交失败保留全部输入。
const props = defineProps({
  // 传入 order 表示编辑既有草稿；null 表示新建
  order: { type: Object, default: null }
})
const emit = defineEmits(['done', 'cancel'])

const candidateSegments = ref([])
const submitting = ref(false)
const errorMsg = ref('')

const form = reactive(blankForm())

function blankForm() {
  return {
    orderNo: '',
    planStartAt: '',
    planEndAt: '',
    ownerName: '',
    remark: '',
    segmentIds: [],
    manualBoundaries: []
  }
}

onMounted(async () => {
  if (props.order) {
    // 编辑草稿：拉详情回填基本信息 + 检修管段 + 人工边界
    try {
      const detail = await api.getMaintenanceOrder(props.order.id)
      form.orderNo = detail.order.orderNo
      form.planStartAt = detail.order.planStartAt
      form.planEndAt = detail.order.planEndAt || ''
      form.ownerName = detail.order.ownerName
      form.remark = detail.order.remark || ''
      form.segmentIds = (detail.impacts || [])
        .filter(i => i.targetType === 'PIPE_SEGMENT' && i.source === 'AUTO')
        .map(i => i.targetId)
      form.manualBoundaries = (detail.impacts || [])
        .filter(i => i.targetType === 'MANUAL')
        .map(i => ({ code: i.targetCode, name: i.targetName, note: i.manualNote }))
    } catch (e) {
      errorMsg.value = e.message
    }
  }
  try {
    candidateSegments.value = (await api.listSegments()) || []
  } catch (e) {
    errorMsg.value = e.message
  }
})

function addBoundary() {
  form.manualBoundaries.push({ code: '', name: '', note: '' })
}
function removeBoundary(idx) {
  form.manualBoundaries.splice(idx, 1)
}

async function submit() {
  errorMsg.value = ''
  if (!form.orderNo.trim()) { errorMsg.value = '检修单编号不能为空'; return }
  if (!form.planStartAt) { errorMsg.value = '请选择计划开始时间'; return }
  if (!form.ownerName.trim()) { errorMsg.value = '施工负责人不能为空'; return }
  if (!form.segmentIds.length) { errorMsg.value = '检修范围至少选择一条待修管段'; return }
  const manuals = []
  for (const [idx, m] of form.manualBoundaries.entries()) {
    if (!m.code.trim() || !m.name.trim() || !m.note.trim()) {
      errorMsg.value = `第 ${idx + 1} 条人工边界的编号、名称、说明都不能为空`
      return
    }
    manuals.push({ code: m.code.trim(), name: m.name.trim(), note: m.note.trim() })
  }

  const payload = {
    orderNo: form.orderNo.trim(),
    planStartAt: form.planStartAt,
    planEndAt: form.planEndAt || null,
    ownerName: form.ownerName.trim(),
    remark: form.remark.trim(),
    segmentIds: form.segmentIds,
    manualBoundaries: manuals
  }
  submitting.value = true
  try {
    if (props.order) {
      await api.updateMaintenanceOrder(props.order.id, payload)
      ElMessage.success('检修单草稿已更新')
    } else {
      await api.createMaintenanceOrder(payload)
      ElMessage.success(`检修单 ${payload.orderNo} 已创建（草稿）`)
    }
    emit('done')
  } catch (e) {
    // 发布/校验失败只提示，表单内容全部保留
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    model-value
    :title="order ? '编辑检修单草稿' : '新建检修单'"
    width="720px" :close-on-click-modal="false" @close="emit('cancel')">
    <div v-if="errorMsg" class="err">{{ errorMsg }}</div>
    <el-form :model="form" label-width="110px" class="dialog-form">
      <el-form-item label="检修单编号" required>
        <el-input v-model="form.orderNo" placeholder="租户内唯一，如 JX-2026-001" :disabled="!!order" />
      </el-form-item>
      <el-form-item label="计划开始时间" required>
        <el-date-picker v-model="form.planStartAt" type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss" placeholder="计划停气/施工开始" style="width: 100%" />
      </el-form-item>
      <el-form-item label="计划结束时间">
        <el-date-picker v-model="form.planEndAt" type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss" placeholder="计划复气/施工结束（选填）" style="width: 100%" />
      </el-form-item>
      <el-form-item label="施工负责人" required>
        <el-input v-model="form.ownerName" placeholder="现场施工负责人" />
      </el-form-item>
      <el-form-item label="检修说明">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="施工内容与安全要求" />
      </el-form-item>
      <el-form-item :label="`检修范围（${form.segmentIds.length}）`" required>
        <el-select v-model="form.segmentIds" multiple collapse-tags collapse-tags-tooltip
          placeholder="选择待修管段（可多选连通管段）" style="width: 100%">
          <el-option v-for="s in candidateSegments" :key="s.id"
            :label="`${s.segCode} ${s.startPoint}→${s.endPoint}（${s.status}）`" :value="s.id" />
        </el-select>
        <div class="field-hint">门站 / 调压站 / 燃气用户由系统沿管段起止点自动关联，无需手选</div>
      </el-form-item>

      <el-form-item label="人工边界对象">
        <div class="boundary-list">
          <div v-for="(m, idx) in form.manualBoundaries" :key="idx" class="boundary-row">
            <el-input v-model="m.code" placeholder="编号/标识" class="b-code" />
            <el-input v-model="m.name" placeholder="名称/位置" class="b-name" />
            <el-input v-model="m.note" placeholder="无法自动判定的边界说明" class="b-note" />
            <el-button link type="danger" @click="removeBoundary(idx)">删除</el-button>
          </div>
          <el-button size="small" @click="addBoundary">+ 新增边界说明</el-button>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ order ? '保存草稿' : '创建草稿' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.boundary-list {
  width: 100%;
}
.boundary-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.b-code { width: 130px; flex: none; }
.b-name { width: 150px; flex: none; }
.b-note { flex: 1; }
</style>
