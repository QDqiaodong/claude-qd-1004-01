<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { newRequestId } from '../../utils/inspection'

const props = defineProps({
  mode: { type: String, required: true }, // rectify / review
  hazard: { type: Object, required: true }
})
const emit = defineEmits(['done', 'cancel'])

const isReview = props.mode === 'review'
// 每次打开生成一个 requestId；整改/复核失败后点「重试」复用同一键，后端幂等
const requestId = newRequestId(isReview ? 'REVIEW' : 'RECTIFY')
const submitting = ref(false)
const errorMsg = ref('')

const form = reactive({
  operator: '',
  note: '',
  // 复核模式：默认通过；驳回必须填写原因
  passed: true
})

async function submit() {
  errorMsg.value = ''
  if (!form.operator.trim()) {
    errorMsg.value = isReview ? '复核人不能为空' : '操作人不能为空'
    return
  }
  if (!form.note.trim()) {
    errorMsg.value = isReview
      ? (form.passed ? '复核意见不能为空' : '驳回原因不能为空')
      : '整改说明不能为空'
    return
  }
  submitting.value = true
  try {
    if (isReview) {
      await api.reviewHazard(props.hazard.id, {
        passed: form.passed,
        note: form.note.trim(),
        operator: form.operator.trim(),
        requestId
      })
      ElMessage.success(form.passed ? '复核通过，隐患已闭环' : '已驳回，隐患退回整改中')
    } else {
      await api.rectifyHazard(props.hazard.id, {
        note: form.note.trim(),
        operator: form.operator.trim(),
        requestId
      })
      ElMessage.success('整改已提交，等待复核')
    }
    emit('done')
  } catch (e) {
    // 逾期整改 / 状态不对 / 网络失败：保留表单内容，仅提示，可直接修改或重试
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    model-value
    :title="isReview ? '隐患复核' : '提交整改'"
    width="520px" :close-on-click-modal="false" @close="emit('cancel')">
    <div v-if="errorMsg" class="err">{{ errorMsg }}</div>

    <el-alert
      :title="`隐患：${hazard.problem}`"
      :description="`整改要求：${hazard.rectifyRequire}　责任人：${hazard.ownerName}　期限：${hazard.dueAt}`"
      type="warning" :closable="false" show-icon style="margin-bottom: 14px" />

    <el-form :model="form" label-width="90px">
      <el-form-item v-if="isReview" label="复核结论" required>
        <el-radio-group v-model="form.passed">
          <el-radio :value="true">复核通过</el-radio>
          <el-radio :value="false">驳回（退回整改中）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item :label="isReview ? (form.passed ? '复核意见' : '驳回原因') : '整改说明'" required>
        <el-input
          v-model="form.note" type="textarea" :rows="3"
          :placeholder="isReview
            ? (form.passed ? '通过意见（不能为空）' : '驳回必须说明原因，历史将完整保留')
            : '整改情况说明（不能为空）；逾期提交将被拒绝'" />
      </el-form-item>
      <el-form-item :label="isReview ? '复核人' : '操作人'" required>
        <el-input v-model="form.operator" :placeholder="isReview ? '复核人姓名' : '责任人姓名'" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button :type="isReview && !form.passed ? 'danger' : 'primary'" :loading="submitting" @click="submit">
        {{ isReview ? (form.passed ? '复核通过' : '确认驳回') : '提交整改' }}（失败可重试，幂等）
      </el-button>
    </template>
  </el-dialog>
</template>
