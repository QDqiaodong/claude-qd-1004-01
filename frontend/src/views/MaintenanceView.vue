<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import MaintenanceOrderDialog from '../components/maintenance/MaintenanceOrderDialog.vue'
import MaintenanceDetail from '../components/maintenance/MaintenanceDetail.vue'
import { moStatusTagType, MO_STATUS } from '../utils/maintenance'
import { useMaintenanceList } from '../utils/useMaintenanceList'

const props = defineProps({ tenant: String })

const { rows, loading, total, error, query, load, search, changePage, changeSize, resetPage } =
  useMaintenanceList()

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

// ---------------- 新建 ----------------
const createVisible = ref(false)
async function onCreated() {
  createVisible.value = false
  await load()
}

watch(() => props.tenant, () => { resetPage() })
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">管段检修 · 影响评估与停复气联动</span>
        <span class="panel-desc">待发布 → 待停气 → 施工中 → 待验收 → 待复气 → 已完成</span>
      </div>

      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="检修单编号 / 施工负责人" clearable
          style="width: 220px" @keyup.enter="search" @clear="search" />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px"
          @change="search">
          <el-option v-for="s in MO_STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="search">查询</el-button>
        <span class="spacer"></span>
        <el-button type="primary" @click="createVisible = true">新建检修单</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="orderNo" label="检修单编号" width="150" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="moStatusTagType(row.status)">{{ row.status }}</el-tag>
            <el-tag v-if="row.frozen" size="small" type="success" effect="plain" style="margin-left:4px">
              已冻结
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ownerName" label="施工负责人" width="110" />
        <el-table-column prop="planStartAt" label="计划开始" width="170" />
        <el-table-column prop="cutOffAt" label="停气时间" width="170" />
        <el-table-column prop="restoredAt" label="复气时间" width="170" />
        <el-table-column prop="restoredBy" label="复气人" width="100" />
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

    <MaintenanceOrderDialog v-if="createVisible" :order="null"
      @done="onCreated" @cancel="createVisible = false" />

    <el-drawer v-model="detailVisible" title="检修详情 · 影响清单与停复气联动" size="68%">
      <MaintenanceDetail v-if="detailVisible" :order-id="detailId" @changed="closeDetail" />
    </el-drawer>
  </div>
</template>
