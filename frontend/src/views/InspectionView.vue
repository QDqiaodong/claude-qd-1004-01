<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import BatchDetail from '../components/inspection/BatchDetail.vue'
import { BATCH_STATUS, batchStatusTagType } from '../utils/inspection'
import { useBatchList } from '../utils/useBatchList'

const props = defineProps({ tenant: String })

// 列表筛选/分页状态由 useBatchList 统一管理：
// 关键字、状态、「只看过期」会随每次请求发出；翻页/改页大小不清条件；
// 过期响应（含切租户前的在途请求）会被丢弃，不会混入列表。
const { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage } = useBatchList()

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

// ---------------- 创建批次 ----------------
const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const candidateSegments = ref([])
const candidateRegulators = ref([])
const candidateUsers = ref([])
const form = ref(blankForm())

function blankForm() {
  return {
    batchNo: '',
    planStartAt: '',
    planEndAt: '',
    ownerName: '',
    remark: '',
    // 选中的对象 {PIPE_SEGMENT:id[], REGULATOR_STATION:id[], GAS_USER:id[]}
    selected: { PIPE_SEGMENT: [], REGULATOR_STATION: [], GAS_USER: [] }
  }
}

async function openCreate() {
  form.value = blankForm()
  createError.value = ''
  createVisible.value = true
  try {
    const [segments, regulators, users] = await Promise.all([
      api.listSegments(), api.listRegulators(), api.listUsers()
    ])
    candidateSegments.value = segments || []
    candidateRegulators.value = regulators || []
    candidateUsers.value = users || []
  } catch (e) {
    // 加载候选对象失败只提示，不关闭对话框、不清空已填表单
    createError.value = e.message
  }
}

function selectedCount() {
  return form.value.selected.PIPE_SEGMENT.length
    + form.value.selected.REGULATOR_STATION.length
    + form.value.selected.GAS_USER.length
}

async function submitCreate() {
  createError.value = ''
  if (!form.value.batchNo.trim()) {
    createError.value = '批次编号不能为空'
    return
  }
  if (!form.value.planStartAt) {
    createError.value = '请选择计划开始时间'
    return
  }
  if (!form.value.remark.trim()) {
    createError.value = '巡检说明不能为空'
    return
  }
  if (selectedCount() === 0) {
    createError.value = '至少关联一个巡检对象'
    return
  }
  const targets = []
  for (const type of Object.keys(form.value.selected)) {
    for (const id of form.value.selected[type]) {
      targets.push({ targetType: type, targetId: id })
    }
  }
  const payload = {
    batchNo: form.value.batchNo.trim(),
    planStartAt: form.value.planStartAt,
    planEndAt: form.value.planEndAt || null,
    ownerName: form.value.ownerName.trim(),
    remark: form.value.remark.trim(),
    targets
  }
  creating.value = true
  try {
    await api.createBatch(payload)
    ElMessage.success(`批次 ${payload.batchNo} 已创建`)
    createVisible.value = false
    await load()
  } catch (e) {
    // 关键：失败不清空表单，仅在顶部展示统一错误，用户可修改后重试
    createError.value = e.message
  } finally {
    creating.value = false
  }
}

function isOverdue(row) {
  return row.status === '待执行' && row.planStartAt && new Date(row.planStartAt) < new Date()
}

watch(() => props.tenant, () => { resetPage() })
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">安全巡检批次 · 隐患闭环</span>
        <span class="panel-desc">待执行 → 执行中 → 待整改 → 待复核 → 已关闭</span>
      </div>

      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="批次编号 / 负责人"
          clearable
          style="width: 200px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px" @change="search">
          <el-option v-for="s in BATCH_STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-checkbox v-model="query.overdueOnly" @change="search">只看过期未执行</el-checkbox>
        <el-button @click="search">查询</el-button>
        <span class="spacer"></span>
        <el-button type="primary" @click="openCreate">新建巡检批次</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="batchNo" label="批次编号" width="160" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="batchStatusTagType(row.status)">{{ row.status }}</el-tag>
            <el-tag v-if="isOverdue(row)" type="danger" size="small" effect="plain" style="margin-left: 4px">
              已过期
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="负责人" width="100" />
        <el-table-column prop="planStartAt" label="计划开始" width="170" />
        <el-table-column prop="startedAt" label="实际开始" width="170" />
        <el-table-column prop="closedBy" label="关闭人" width="100" />
        <el-table-column prop="remark" label="说明" min-width="180" show-overflow-tooltip />
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

    <!-- 创建批次对话框：失败保留表单 -->
    <el-dialog v-model="createVisible" title="新建巡检批次" width="720px" :close-on-click-modal="false">
      <div v-if="createError" class="err">{{ createError }}</div>
      <el-form :model="form" label-width="110px" class="dialog-form">
        <el-form-item label="批次编号" required>
          <el-input v-model="form.batchNo" placeholder="租户内唯一，如 XJ-2026-001" />
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
        <el-form-item label="负责人" required>
          <el-input v-model="form.ownerName" placeholder="片区运营人员" />
        </el-form-item>
        <el-form-item label="巡检说明" required>
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="本次巡检范围与要求（不能为空）" />
        </el-form-item>
        <el-form-item :label="`关联对象（${selectedCount()}）`" required>
          <div class="target-picker">
            <div class="target-group">
              <div class="target-group-title">管段</div>
              <el-select
                v-model="form.selected.PIPE_SEGMENT"
                multiple collapse-tags collapse-tags-tooltip
                placeholder="选择管段" style="width: 100%"
              >
                <el-option
                  v-for="s in candidateSegments" :key="s.id"
                  :label="`${s.segCode} ${s.startPoint}→${s.endPoint}`" :value="s.id"
                />
              </el-select>
            </div>
            <div class="target-group">
              <div class="target-group-title">调压站</div>
              <el-select
                v-model="form.selected.REGULATOR_STATION"
                multiple collapse-tags collapse-tags-tooltip
                placeholder="选择调压站" style="width: 100%"
              >
                <el-option
                  v-for="r in candidateRegulators" :key="r.id"
                  :label="`${r.stationCode} ${r.stationName}`" :value="r.id"
                />
              </el-select>
            </div>
            <div class="target-group">
              <div class="target-group-title">燃气用户</div>
              <el-select
                v-model="form.selected.GAS_USER"
                multiple collapse-tags collapse-tags-tooltip
                placeholder="选择燃气用户" style="width: 100%"
              >
                <el-option
                  v-for="u in candidateUsers" :key="u.id"
                  :label="`${u.userNo} ${u.userName}`" :value="u.id"
                />
              </el-select>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建批次</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="批次详情与隐患闭环" size="62%">
      <BatchDetail v-if="detailVisible" :batch-id="detailId" @changed="(v) => closeDetail(v)" />
    </el-drawer>
  </div>
</template>
