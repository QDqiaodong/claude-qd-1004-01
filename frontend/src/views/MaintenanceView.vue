<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import OrderDetail from '../components/maintenance/OrderDetail.vue'
import { MAINT_STATUS, maintStatusTagType } from '../utils/maintenance'
import { useOrderList } from '../utils/useOrderList'

const props = defineProps({ tenant: String })

// 列表筛选/分页状态由 useOrderList 统一管理：
// 关键字、状态随每次请求发出；翻页/改页大小不清条件；过期响应（含切租户前的在途请求）会被丢弃。
const { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage } = useOrderList()

// 查询失败：保留用户已填条件，仅提示原因
watch(error, (msg) => { if (msg) ElMessage.error(msg) })

// ---------------- 详情抽屉 ----------------
const detailVisible = ref(false)
const detailId = ref(null)
function openDetail(row) {
  detailId.value = row.id
  detailVisible.value = true
}
function closeDetail(changed) {
  detailVisible.value = false
  if (changed) load()
}

// ---------------- 新建 / 编辑检修单 ----------------
const editVisible = ref(false)
const editingId = ref(null)
const saving = ref(false)
const editError = ref('')
const candidateSegments = ref([])
const form = ref(blankForm())

function blankForm() {
  return {
    orderNo: '',
    planStartAt: '',
    planEndAt: '',
    leaderName: '',
    scopeRemark: '',
    segmentIds: [],
    version: null
  }
}

async function loadSegments() {
  try {
    const segments = await api.listSegments()
    // 报废管段走报废流程，不能纳入检修范围
    candidateSegments.value = (segments || []).filter(s => s.status !== '报废')
  } catch (e) {
    // 加载候选管段失败只提示，不关闭对话框、不清空已填表单
    editError.value = e.message
  }
}

async function openCreate() {
  editingId.value = null
  form.value = blankForm()
  editError.value = ''
  editVisible.value = true
  await loadSegments()
}

async function openEdit(order) {
  detailVisible.value = false
  editingId.value = order.id
  // 用详情里的当前值预填，版本随单携带（后端统一校验版本）
  const detail = await api.getOrder(order.id)
  form.value = {
    orderNo: detail.order.orderNo,
    planStartAt: detail.order.planStartAt,
    planEndAt: detail.order.planEndAt || '',
    leaderName: detail.order.leaderName,
    scopeRemark: detail.order.scopeRemark,
    segmentIds: (detail.segments || []).map(s => s.segmentId),
    version: detail.order.version
  }
  editError.value = ''
  editVisible.value = true
  await loadSegments()
}

function validateForm() {
  if (!editingId.value && !form.value.orderNo.trim()) return '检修单号不能为空'
  if (!form.value.planStartAt) return '请选择计划开始时间'
  if (form.value.planEndAt && form.value.planEndAt < form.value.planStartAt) {
    return '计划结束时间不能早于计划开始时间'
  }
  if (!form.value.leaderName.trim()) return '施工负责人不能为空'
  if (!form.value.scopeRemark.trim()) return '检修范围说明不能为空'
  if (form.value.segmentIds.length === 0) return '至少选择一段待修管段'
  return ''
}

async function submitForm() {
  editError.value = validateForm()
  if (editError.value) return
  saving.value = true
  try {
    if (editingId.value) {
      await api.updateOrder(editingId.value, {
        planStartAt: form.value.planStartAt,
        planEndAt: form.value.planEndAt || null,
        leaderName: form.value.leaderName.trim(),
        scopeRemark: form.value.scopeRemark.trim(),
        segmentIds: form.value.segmentIds,
        version: form.value.version
      })
      ElMessage.success('检修单已更新，自动影响已重建')
    } else {
      await api.createOrder({
        orderNo: form.value.orderNo.trim(),
        planStartAt: form.value.planStartAt,
        planEndAt: form.value.planEndAt || null,
        leaderName: form.value.leaderName.trim(),
        scopeRemark: form.value.scopeRemark.trim(),
        segmentIds: form.value.segmentIds
      })
      ElMessage.success(`检修单 ${form.value.orderNo.trim()} 已创建，影响清单已自动关联`)
    }
    editVisible.value = false
    await load()
  } catch (e) {
    // 关键：失败不清空表单，仅在顶部展示统一错误，用户可修改后重试
    editError.value = e.message
  } finally {
    saving.value = false
  }
}

watch(() => props.tenant, () => { resetPage() })
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">管段检修 · 停复气联动</span>
        <span class="panel-desc">待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成</span>
      </div>

      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="检修单号 / 施工负责人"
          clearable
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px" @change="search">
          <el-option v-for="s in MAINT_STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="search">查询</el-button>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建检修单</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="orderNo" label="检修单号" width="150" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="maintStatusTagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="leaderName" label="施工负责人" width="110" />
        <el-table-column prop="planStartAt" label="计划开始" width="170" />
        <el-table-column prop="publishedBy" label="发布人" width="90">
          <template #default="{ row }">{{ row.publishedBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="stoppedBy" label="停气人" width="90">
          <template #default="{ row }">{{ row.stoppedBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="restoredBy" label="复气人" width="90">
          <template #default="{ row }">{{ row.restoredBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="scopeRemark" label="检修范围说明" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openDetail(row)">详情/处置</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-sizes="[10, 20, 50]"
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          @current-change="changePage"
          @size-change="changeSize"
        />
      </div>
    </div>

    <!-- 新建 / 编辑检修单对话框：失败保留表单 -->
    <el-dialog v-model="editVisible" :title="editingId ? '编辑检修单（待发布）' : '新建检修单'"
               width="640px" :close-on-click-modal="false">
      <div v-if="editError" class="err">{{ editError }}</div>
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="检修单号" required>
          <el-input v-model="form.orderNo" :disabled="!!editingId" placeholder="租户内唯一，如 JX-2026-001" />
        </el-form-item>
        <el-form-item label="计划开始时间" required>
          <el-date-picker
            v-model="form.planStartAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择计划开始时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="计划结束时间">
          <el-date-picker
            v-model="form.planEndAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选填"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="施工负责人" required>
          <el-input v-model="form.leaderName" placeholder="施工现场负责人" />
        </el-form-item>
        <el-form-item label="检修范围说明" required>
          <el-input v-model="form.scopeRemark" type="textarea" :rows="2" placeholder="本次检修范围与要求（不能为空）" />
        </el-form-item>
        <el-form-item :label="`检修管段（${form.segmentIds.length}）`" required>
          <el-select
            v-model="form.segmentIds"
            multiple collapse-tags collapse-tags-tooltip
            placeholder="选择待修管段（报废管段不可选）" style="width: 100%"
          >
            <el-option
              v-for="s in candidateSegments" :key="s.id"
              :label="`${s.segCode} ${s.startPoint}→${s.endPoint}（${s.status}）`" :value="s.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">
          {{ editingId ? '保存修改' : '创建检修单' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="检修单详情 · 影响评估与停复气" size="64%">
      <OrderDetail v-if="detailVisible" :order-id="detailId"
                   @changed="(v) => closeDetail(v)" @edit="openEdit" />
    </el-drawer>
  </div>
</template>
