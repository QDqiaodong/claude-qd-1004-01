import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import MaintenanceView from '../src/views/MaintenanceView.vue'
import MaintenanceOrderDialog from '../src/components/maintenance/MaintenanceOrderDialog.vue'
import MaintenanceDetail from '../src/components/maintenance/MaintenanceDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    listMaintenanceOrders: vi.fn(),
    listSegments: vi.fn().mockResolvedValue([]),
    createMaintenanceOrder: vi.fn(),
    getMaintenanceOrder: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const page = (items, total) => ({ items, total, page: 0, size: 10, totalPages: 1 })

function mountView() {
  return mount(MaintenanceView, {
    props: { tenant: 'A片区' },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

function findDialogIn(root, text) {
  // 本测试环境下 el-dialog 内容渲染在组件内（teleport 被打桩），body 也兜底找一遍
  const all = [
    ...Array.from(root.querySelectorAll('.el-dialog')),
    ...Array.from(document.body.querySelectorAll('.el-dialog'))
  ]
  return all.find(d => d.textContent.includes(text))
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listMaintenanceOrders.mockResolvedValue(page([], 0))
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('检修单列表', () => {
  it('渲染检修单状态与冻结标记，空列表显示空态', async () => {
    api.listMaintenanceOrders.mockResolvedValue(page([
      { id: 1, orderNo: 'JX-001', status: '待发布', frozen: false, ownerName: '张工' },
      { id: 2, orderNo: 'JX-002', status: '待复气', frozen: true, ownerName: '李工' }
    ], 2))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('JX-001')
    expect(wrapper.text()).toContain('JX-002')
    expect(wrapper.text()).toContain('已冻结')
  })

  it('关键字查询与状态筛选都从第一页发出', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[placeholder="检修单编号 / 施工负责人"]').setValue('JX-09')
    await wrapper.findAll('button').find(b => b.text() === '查询').trigger('click')
    await flushPromises()
    expect(api.listMaintenanceOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'JX-09', page: 0 })
    )

    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', '施工中')
    wrapper.findComponent(ElSelect).vm.$emit('change', '施工中')
    await flushPromises()
    expect(api.listMaintenanceOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '施工中', page: 0 })
    )
  })

  it('翻页保留筛选，切换片区回第一页', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[placeholder="检修单编号 / 施工负责人"]').setValue('王工')
    await wrapper.findAll('button').find(b => b.text() === '查询').trigger('click')
    await flushPromises()
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listMaintenanceOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '王工', page: 1 })
    )

    await wrapper.setProps({ tenant: 'B片区' })
    await flushPromises()
    expect(api.listMaintenanceOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0 })
    )
  })
})

describe('新建检修单对话框', () => {
  it('不选管段不允许提交；补齐后调用创建接口并刷新列表', async () => {
    api.listSegments.mockResolvedValue([
      { id: 11, segCode: 'PS-1', startPoint: 'A', endPoint: 'B', status: '正常' }
    ])
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('button').find(b => b.text() === '新建检修单').trigger('click')
    await flushPromises()

    const dialogAt = () => findDialogIn(wrapper.element, '新建检修单')
    let dialog = dialogAt()
    const clickCreate = async () => {
      const btn = dialogAt().querySelectorAll('button')
      Array.from(btn).find(b => b.textContent.includes('创建草稿')).click()
      await flushPromises()
    }

    // 先填好除检修范围外的必填项，专门验证「范围为空」这一条拦截
    const dialogVm = wrapper.findComponent(MaintenanceOrderDialog)
    dialogVm.vm.form.orderNo = 'JX-NEW'
    dialogVm.vm.form.planStartAt = '2026-10-10T08:00:00'
    dialogVm.vm.form.ownerName = '施工负责人甲'

    // 未选管段：前端拦截，不发请求
    await clickCreate()
    expect(dialog.textContent).toContain('检修范围至少选择一条待修管段')
    expect(api.createMaintenanceOrder).not.toHaveBeenCalled()

    // 选择管段后提交成功
    dialogVm.vm.form.segmentIds = [11]
    await clickCreate()

    expect(api.createMaintenanceOrder).toHaveBeenCalledTimes(1)
    const payload = api.createMaintenanceOrder.mock.calls[0][0]
    expect(payload).toMatchObject({
      orderNo: 'JX-NEW', ownerName: '施工负责人甲', segmentIds: [11]
    })
    expect(payload.manualBoundaries).toEqual([])
    // 创建成功后列表刷新
    expect(api.listMaintenanceOrders.mock.calls.length).toBeGreaterThan(1)
  })

  it('人工边界缺说明时拦截提交，补齐后随请求发出', async () => {
    api.listSegments.mockResolvedValue([
      { id: 12, segCode: 'PS-2', startPoint: 'A', endPoint: 'B', status: '待修' }
    ])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '新建检修单').trigger('click')
    await flushPromises()

    const dialogVm = wrapper.findComponent(MaintenanceOrderDialog)
    dialogVm.vm.form.orderNo = 'JX-BND'
    dialogVm.vm.form.planStartAt = '2026-10-10T08:00:00'
    dialogVm.vm.form.ownerName = '乙'
    dialogVm.vm.form.segmentIds = [12]
    dialogVm.vm.form.manualBoundaries.push({ code: 'BND-1', name: '阀井', note: '' })

    const dialogAt = () => findDialogIn(wrapper.element, '新建检修单')
    Array.from(dialogAt().querySelectorAll('button'))
      .find(b => b.textContent.includes('创建草稿')).click()
    await flushPromises()
    expect(api.createMaintenanceOrder).not.toHaveBeenCalled()

    dialogVm.vm.form.manualBoundaries[0].note = '产权分界点'
    Array.from(dialogAt().querySelectorAll('button'))
      .find(b => b.textContent.includes('创建草稿')).click()
    await flushPromises()
    expect(api.createMaintenanceOrder.mock.calls[0][0].manualBoundaries)
      .toEqual([{ code: 'BND-1', name: '阀井', note: '产权分界点' }])
  })
})

describe('检修详情抽屉', () => {
  it('点详情拉取详情并渲染详情组件', async () => {
    api.listMaintenanceOrders.mockResolvedValue(page([
      { id: 77, orderNo: 'JX-D', status: '待发布', frozen: false, ownerName: '赵工' }
    ], 1))
    api.getMaintenanceOrder.mockResolvedValue(
      { order: { id: 77, status: '待发布' }, impacts: [] })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('button').find(b => b.text() === '详情/处置').trigger('click')
    await flushPromises()
    expect(api.getMaintenanceOrder).toHaveBeenCalledWith(77)
    expect(wrapper.findComponent(MaintenanceDetail).exists()).toBe(true)
  })
})
