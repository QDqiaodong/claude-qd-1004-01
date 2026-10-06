import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElButton, ElDatePicker, ElInput, ElMessage, ElOption, ElPagination, ElSelect } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import MaintenanceView from '../src/views/MaintenanceView.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    listOrders: vi.fn(),
    listSegments: vi.fn().mockResolvedValue([]),
    createOrder: vi.fn(),
    updateOrder: vi.fn(),
    getOrder: vi.fn()
  }
}))

// el-table 依赖 ResizeObserver，jsdom 没有，补一个空实现
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const page = (items, total) => ({ items, total, page: 0, size: 10, totalPages: 1 })

const orderRow = {
  id: 1,
  orderNo: 'JX-2026-001',
  status: '待发布',
  leaderName: '施工队长王',
  planStartAt: '2026-10-10T08:00:00',
  publishedBy: null,
  stoppedBy: null,
  restoredBy: null,
  scopeRemark: '更换老旧管段'
}

function mountView() {
  return mount(MaintenanceView, {
    props: { tenant: 'A片区' },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

function searchButton(wrapper) {
  return wrapper.findAll('button').find(b => b.text() === '查询')
}

/** 对话框内容 teleport 到 body，但组件仍在 vnode 树里，findComponent 可直达 */
function inputByPlaceholder(wrapper, placeholder) {
  return wrapper.findAllComponents(ElInput)
    .find(i => (i.props('placeholder') || '').includes(placeholder))
}

function datePickerByPlaceholder(wrapper, placeholder) {
  return wrapper.findAllComponents(ElDatePicker)
    .find(i => (i.props('placeholder') || '').includes(placeholder))
}

function selectByPlaceholder(wrapper, placeholder) {
  return wrapper.findAllComponents(ElSelect)
    .find(i => (i.props('placeholder') || '').includes(placeholder))
}

function buttonByText(wrapper, text) {
  return wrapper.findAllComponents(ElButton).find(b => b.text() === text)
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listOrders.mockResolvedValue(page([], 0))
  document.body.innerHTML = ''
})

describe('检修单列表', () => {
  it('加载并渲染检修单行与状态标签', async () => {
    api.listOrders.mockResolvedValue(page([orderRow], 1))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('JX-2026-001')
    expect(wrapper.text()).toContain('待发布')
    expect(wrapper.text()).toContain('施工队长王')
    expect(api.listOrders).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 10 }))
  })

  it('输入单号/负责人后查询，关键字与状态必须随请求发出且从第一页重新计算', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('input[placeholder="检修单号 / 施工负责人"]').setValue('JX-2026')
    const statusSelect = wrapper.findComponent(ElSelect)
    statusSelect.vm.$emit('update:modelValue', '待停气')
    statusSelect.vm.$emit('change', '待停气')
    await flushPromises()
    expect(api.listOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '待停气', page: 0 })
    )

    await searchButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.listOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'JX-2026', status: '待停气', page: 0, size: 10 })
    )
  })

  it('翻页保留筛选条件；切换片区后从第一页重新加载', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[placeholder="检修单号 / 施工负责人"]').setValue('王')
    await searchButton(wrapper).trigger('click')
    await flushPromises()

    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '王', page: 1 })
    )

    await wrapper.setProps({ tenant: 'B片区' })
    await flushPromises()
    expect(api.listOrders).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0 }))
  })

  it('查询失败时保留已填条件并提示原因', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
    const wrapper = mountView()
    await flushPromises()

    const input = wrapper.find('input[placeholder="检修单号 / 施工负责人"]')
    await input.setValue('JX')
    api.listOrders.mockRejectedValueOnce(new Error('服务异常：连接超时'))
    await searchButton(wrapper).trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('服务异常：连接超时')
    expect(input.element.value).toBe('JX')
    errorSpy.mockRestore()
  })
})

describe('新建检修单', () => {
  const segments = [
    { id: 11, segCode: 'PS-A01', startPoint: '青云门站', endPoint: '绿谷小区', status: '待修' },
    { id: 12, segCode: 'PS-A02', startPoint: '绿谷小区', endPoint: '绿谷小学', status: '正常' },
    { id: 13, segCode: 'PS-A03', startPoint: '老城门站', endPoint: '玻璃厂', status: '报废' }
  ]

  async function fillValidForm(wrapper) {
    inputByPlaceholder(wrapper, '租户内唯一').vm.$emit('update:modelValue', 'JX-2026-002')
    datePickerByPlaceholder(wrapper, '计划开始时间').vm.$emit('update:modelValue', '2026-10-10T08:00:00')
    inputByPlaceholder(wrapper, '施工现场负责人').vm.$emit('update:modelValue', '施工队长王')
    // 检修范围说明是 textarea（ElInput type=textarea），按 placeholder 命中
    inputByPlaceholder(wrapper, '本次检修范围与要求').vm.$emit('update:modelValue', '更换老旧管段')
    selectByPlaceholder(wrapper, '选择待修管段').vm.$emit('update:modelValue', [11, 12])
    await flushPromises()
  }

  it('报废管段不出现在候选中；必填校验拦截时不发请求', async () => {
    api.listSegments.mockResolvedValue(segments)
    const wrapper = mountView()
    await flushPromises()
    await buttonByText(wrapper, '新建检修单').trigger('click')
    await flushPromises()

    // 报废管段被过滤，只剩两段可选（选项值为管段 id；分页大小选项也是数字，需排除）
    const segmentSelect = selectByPlaceholder(wrapper, '选择待修管段')
    expect(segmentSelect).toBeTruthy()
    expect(api.listSegments).toHaveBeenCalled()
    const optionValues = wrapper.findAllComponents(ElOption)
      .map(o => o.props('value'))
      .filter(v => typeof v === 'number')
    expect(optionValues).toContain(11)
    expect(optionValues).toContain(12)
    expect(optionValues).not.toContain(13)

    // 空表单直接提交：前端校验拦截，不调后端，错误提示展示在对话框内
    await buttonByText(wrapper, '创建检修单').trigger('click')
    await flushPromises()
    expect(api.createOrder).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('检修单号不能为空')
  })

  it('创建成功：载荷正确、关闭对话框并刷新列表', async () => {
    api.listSegments.mockResolvedValue(segments)
    api.createOrder.mockResolvedValue({ id: 9 })
    const wrapper = mountView()
    await flushPromises()
    await buttonByText(wrapper, '新建检修单').trigger('click')
    await flushPromises()
    await fillValidForm(wrapper)

    const callsBefore = api.listOrders.mock.calls.length
    await buttonByText(wrapper, '创建检修单').trigger('click')
    await flushPromises()

    expect(api.createOrder).toHaveBeenCalledWith({
      orderNo: 'JX-2026-002',
      planStartAt: '2026-10-10T08:00:00',
      planEndAt: null,
      leaderName: '施工队长王',
      scopeRemark: '更换老旧管段',
      segmentIds: [11, 12]
    })
    expect(api.listOrders.mock.calls.length).toBeGreaterThan(callsBefore)
  })

  it('创建失败：错误提示展示且已填表单不清空', async () => {
    api.listSegments.mockResolvedValue(segments)
    api.createOrder.mockRejectedValue(new Error('检修单号已存在：JX-2026-002'))
    const wrapper = mountView()
    await flushPromises()
    await buttonByText(wrapper, '新建检修单').trigger('click')
    await flushPromises()
    await fillValidForm(wrapper)

    await buttonByText(wrapper, '创建检修单').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('检修单号已存在：JX-2026-002')
    // 表单保留：单号与负责人仍是已填值，用户可修改后重试
    expect(inputByPlaceholder(wrapper, '租户内唯一').props('modelValue')).toBe('JX-2026-002')
    expect(inputByPlaceholder(wrapper, '施工现场负责人').props('modelValue')).toBe('施工队长王')
  })
})
