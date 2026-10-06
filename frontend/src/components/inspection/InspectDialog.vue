<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { RISK_LEVELS, targetTypeLabel, newRequestId } from '../../utils/inspection'

const props = defineProps({
  batchId: { type: Number, required: true },
  target: { type: Object, required: true }
})
const emit = defineEmits(['done', 'cancel'])

// 每个对话框实例固定一个 requestId：网络抖动/用户点重试时复用，后端幂等回放
const requestId = newRequestId('INSPECT')
const submitting = ref(false)
const errorMsg = ref('')

const form = reactive({
  result: '正常',
  operator: '',
  hazard: {
    problem: '',
    riskLevel: '中',
    rectifyRequire: '',
    ownerName: '',
    dueAt: ''
  }
})

async function submit() {
  errorMsg.value = ''
  if (!form.operator.trim()) {
    errorMsg.value = '巡检人不能为空'
    return
  }
  if (form.result === '隐患') {
    const h = form.hazard
    if (!h.problem.trim()) { errorMsg.value = '隐患问题描述不能为空'; return }
    if (!h.rectifyRequire.trim()) { errorMsg.value = '整改要求不能为空'; return }
    if (!h.ownerName.trim()) { errorMsg.value = '责任人不能为空'; return }
    if (!h.dueAt) { errorMsg.value = '请选择整改期限'; return }
  }
  const payload = {
    result: form.result,
    operator: form.operator.trim(),
    requestId,
    hazard: form.result === '隐患' ? {
      problem: form.hazard.problem.trim(),
      riskLevel: form.hazard.riskLevel,
      rectifyRequire: form.hazard.rectifyRequire.trim(),
      ownerName: form.hazard.ownerName.trim(),
      dueAt: form.hazard.dueAt
    } : undefined
  }
  submitting.value = true
  try {
    await api.inspectTarget(props.batchId, props.target.linkId, payload)
    ElMessage.success('巡检结果已登记')
    emit('done')
  } catch (e) {
    // 失败保留全部输入，仅提示（重复隐患/过期等统一错误结构）
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    model-value
    :title="`登记巡检结果 · ${targetTypeLabel(target.targetType)} ${target.targetCode}`"
    width="560px" :close-on-click-modal="false" @close="emit('cancel')">
    <div v-if="errorMsg" class="err">{{ errorMsg }}</div>
    <el-form :model="form" label-width="100px">
      <el-form-item label="巡检结果" required>
        <el-radio-group v-model="form.result">
          <el-radio value="正常">正常</el-radio>
          <el-radio value="隐患">隐患</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="巡检人" required>
        <el-input v-model="form.operator" placeholder="现场巡检人" />
      </el-form-item>

      <template v-if="form.result === '隐患'">
        <el-divider content-position="left">隐患信息</el-divider>
        <el-form-item label="问题描述" required>
          <el-input v-model="form.hazard.problem" type="textarea" :rows="2" placeholder="如：法兰渗漏（不能为空）" />
        </el-form-item>
        <el-form-item label="风险等级" required>
          <el-radio-group v-model="form.hazard.riskLevel">
            <el-radio v-for="r in RISK_LEVELS" :key="r" :value="r">{{ r }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="整改要求" required>
          <el-input v-model="form.hazard.rectifyRequire" type="textarea" :rows="2" placeholder="具体整改措施" />
        </el-form-item>
        <el-form-item label="责任人" required>
          <el-input v-model="form.hazard.ownerName" placeholder="整改责任人" />
        </el-form-item>
        <el-form-item label="整改期限" required>
          <el-date-picker v-model="form.hazard.dueAt" type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="逾期提交整改将被拒绝" style="width: 100%" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交（失败可重试，幂等）</el-button>
    </template>
  </el-dialog>
</template>
