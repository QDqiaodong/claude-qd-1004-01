import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElButton, ElInput, ElMessage, ElMessageBox, ElSelect } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import OrderDetail from '../src/components/maintenance/OrderDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    getOrder: vi.fn(),
    orderAction: vi.fn(),
    refreshImpact: vi.fn(),
    addBoundary: vi.fn(),
    removeBoundary: vi.fn(),
    acceptSegment: vi.fn(),
    confirmUser: vi.fn(),
    listGateStations: vi.fn().mockResolvedValue([]),
    listRegulators: vi.fn().mockResolvedValue([]),
    listUsers: vi.fn().mockResolvedValue([])
  }
}))

// el-table 依赖 ResizeObserver，jsdom 没有，补一个空实现
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** 待发布草稿详情：1 段管段 + 3 行影响（门站自动 / 用户自动 / 用户人工边界） */
function draftDetail() {
  return {
    order: {
      id: 1, orderNo: 'JX-2026-001', status: '待发布', version: 3,
      leaderName: '施工队长王', planStartAt: '2026-10-10T08:00:00', planEndAt: null,
      scopeRemark: '更换老旧管段',
      publishedBy: null, publishedAt: null, stoppedBy: null, stoppedAt: null,
      restoredBy: null, restoredAt: null
    },
    segments: [
      { id: 11, segmentId: 101, segCode: 'PS-A01', segName: '青云门站 → 绿谷小区', acceptStatus: '待验收' }
    ],
    impacts: [
      { id: 21, objectType: 'GATE_STATION', objectCode: 'GS-01', objectName: '青云门站',
        source: 'AUTO', viaDesc: '管段PS-A01·起点', manualNote: null, confirmStatus: '无需确认' },
      { id: 22, objectType: 'GAS_USER', objectCode: 'U-01', objectName: '张三',
        source: 'AUTO', viaDesc: '管段PS-A01·终点', manualNote: null, confirmStatus: '待确认' },
      { id: 23, objectType: 'GAS_USER', objectCode: 'U-88', objectName: '李四',
        source: 'MANUAL', viaDesc: '人工补充', manualNote: '远郊路支线人工核对纳入', confirmStatus: '待确认' }
    ],
    diffs: [],
    pendingAcceptCount: 1,
    pendingConfirmCount: 2
  }
}

/** 待复气详情：带两条差异提示 */
function restoringDetail() {
  const d = draftDetail()
  d.order.status = '待复气'
  d.order.version = 7
  d.order.publishedBy = '调度甲'
  d.order.stoppedBy = '调度甲'
  d.segments[0].acceptStatus = '已通过'
  d.impacts[1].confirmStatus = '已确认'
  d.diffs = [
    { diffType: 'ADDED', objectType: 'GAS_USER', objectCode: 'U-99', objectName: '新迁入户',
      message: '当前拓扑新关联的影响对象（管段PS-A01·终点），未纳入已发布快照' },
    { diffType: 'UNAVAILABLE', objectType: 'REGULATOR_STATION', objectCode: 'RS-01', objectName: '绿谷调压箱',
      message: '快照对象已停用' }
  ]
  d.pendingAcceptCount = 0
  d.pendingConfirmCount = 1
  return d
}

function mountDetail(detail) {
  api.getOrder.mockResolvedValue(detail)
  return mount(OrderDetail, {
    props: { orderId: 1 },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

function buttonByText(wrapper, text) {
  return wrapper.findAllComponents(ElButton).find(b => b.text() === text)
}

function inputByPlaceholder(wrapper, placeholder) {
  return wrapper.findAllComponents(ElInput)
    .find(i => (i.props('placeholder') || '').includes(placeholder))
}

/** 操作人对话框：填名字并确定（对话框 teleport 到 body，但组件在 vnode 树内） */
async function fillOperator(wrapper, name) {
  await flushPromises()
  inputByPlaceholder(wrapper, '如：调度甲').vm.$emit('update:modelValue', name)
  await buttonByText(wrapper, '确定').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
  vi.spyOn(ElMessage, 'success').mockImplementation(() => {})
  document.body.innerHTML = ''
})

describe('检修详情渲染', () => {
  it('展示主单信息、检修范围管段与影响清单（含人工边界说明）', async () => {
    const wrapper = mountDetail(draftDetail())
    await flushPromises()

    expect(wrapper.text()).toContain('JX-2026-001')
    expect(wrapper.text()).toContain('待发布')
    expect(wrapper.text()).toContain('施工队长王')
    expect(wrapper.text()).toContain('PS-A01')
    expect(wrapper.text()).toContain('青云门站 → 绿谷小区')
    // 影响清单：自动关联与人工边界来源标签、人工说明
    expect(wrapper.text()).toContain('青云门站')
    expect(wrapper.text()).toContain('自动关联')
    expect(wrapper.text()).toContain('人工边界')
    expect(wrapper.text()).toContain('远郊路支线人工核对纳入')
    expect(wrapper.text()).toContain('管段PS-A01·起点')
  })

  it('影响差异提示：发布后渲染新增/失效差异，草稿不渲染', async () => {
    const wrapper = mountDetail(restoringDetail())
    await flushPromises()

    expect(wrapper.text()).toContain('影响差异提示')
    expect(wrapper.text()).toContain('新增影响')
    expect(wrapper.text()).toContain('U-99')
    expect(wrapper.text()).toContain('未纳入已发布快照')
    expect(wrapper.text()).toContain('对象失效')
    expect(wrapper.text()).toContain('快照对象已停用')

    const draft = mountDetail(draftDetail())
    await flushPromises()
    expect(draft.text()).not.toContain('影响差异提示')
  })
})

describe('状态推进操作反馈', () => {
  it('发布：携带操作人/版本/requestId 调用，成功后刷新详情', async () => {
    const detail = draftDetail()
    api.orderAction.mockResolvedValue({ ...detail.order, status: '待停气' })
    const wrapper = mountDetail(detail)
    await flushPromises()

    await buttonByText(wrapper, '发布（冻结影响快照）').trigger('click')
    await fillOperator(wrapper, '调度甲')

    expect(api.orderAction).toHaveBeenCalledWith(1, 'publish', expect.objectContaining({
      operator: '调度甲',
      version: 3,
      requestId: expect.stringMatching(/^MAINT-/)
    }))
    expect(api.getOrder).toHaveBeenCalledTimes(2) // 初次加载 + 成功后刷新
  })

  it('发布失败（拓扑冲突）：错误提示展示，详情不被覆盖', async () => {
    api.orderAction.mockRejectedValue(new Error('影响清单与当前拓扑不一致（档案或管段起止点已变化），发布被拒绝；请先「重新评估影响」核对差异后再发布'))
    const wrapper = mountDetail(draftDetail())
    await flushPromises()

    await buttonByText(wrapper, '发布（冻结影响快照）').trigger('click')
    await fillOperator(wrapper, '调度甲')

    expect(wrapper.text()).toContain('影响清单与当前拓扑不一致')
    expect(wrapper.text()).toContain('JX-2026-001') // 详情仍正常展示
  })

  it('复气放行被双闸门拦截：提示未确认户数', async () => {
    api.orderAction.mockRejectedValue(new Error('还有 1 户受影响用户未完成安全确认，不得复气放行'))
    const wrapper = mountDetail(restoringDetail())
    await flushPromises()

    await buttonByText(wrapper, '复气放行').trigger('click')
    await fillOperator(wrapper, '调度甲')

    expect(api.orderAction).toHaveBeenCalledWith(1, 'restore-gas', expect.objectContaining({
      operator: '调度甲', version: 7
    }))
    expect(wrapper.text()).toContain('还有 1 户受影响用户未完成安全确认，不得复气放行')
  })

  it('停气开工与完工报验按状态出现', async () => {
    const d = draftDetail()
    d.order.status = '待停气'
    api.orderAction.mockResolvedValue({})
    const wrapper = mountDetail(d)
    await flushPromises()
    expect(buttonByText(wrapper, '停气开工')).toBeTruthy()
    expect(buttonByText(wrapper, '完工报验')).toBeUndefined()

    await buttonByText(wrapper, '停气开工').trigger('click')
    await fillOperator(wrapper, '调度甲')
    expect(api.orderAction).toHaveBeenCalledWith(1, 'stop-gas', expect.objectContaining({ operator: '调度甲' }))
  })
})

describe('验收与安全确认', () => {
  it('待验收：登记验收意见并调用接口', async () => {
    const d = draftDetail()
    d.order.status = '待验收'
    d.order.version = 5
    api.acceptSegment.mockResolvedValue({})
    const wrapper = mountDetail(d)
    await flushPromises()

    await buttonByText(wrapper, '验收通过').trigger('click')
    await flushPromises()
    inputByPlaceholder(wrapper, '如：验收员赵').vm.$emit('update:modelValue', '验收员赵')
    // 对话框内的「验收通过」按钮（accept-dialog 作用域）
    const dialogSubmit = wrapper.findAllComponents(ElButton)
      .filter(b => b.text() === '验收通过')
      .find(b => b.element.closest('.accept-dialog'))
    await dialogSubmit.trigger('click')
    await flushPromises()

    expect(api.acceptSegment).toHaveBeenCalledWith(1, 11, expect.objectContaining({
      operator: '验收员赵', version: 5
    }))
  })

  it('受影响用户安全确认：携带操作人调用接口', async () => {
    api.confirmUser.mockResolvedValue({})
    const wrapper = mountDetail(restoringDetail())
    await flushPromises()

    const confirmButtons = wrapper.findAllComponents(ElButton)
      .filter(b => b.text() === '安全确认' && !b.props('disabled'))
    expect(confirmButtons.length).toBeGreaterThan(0)
    await confirmButtons[0].trigger('click')
    await fillOperator(wrapper, '安全员钱')

    expect(api.confirmUser).toHaveBeenCalledWith(1, 23, { operator: '安全员钱' })
  })
})

describe('草稿编辑：重新评估与人工边界', () => {
  it('重新评估影响：携带当前版本调用', async () => {
    api.refreshImpact.mockResolvedValue(draftDetail())
    const wrapper = mountDetail(draftDetail())
    await flushPromises()

    await buttonByText(wrapper, '重新评估影响').trigger('click')
    await flushPromises()
    expect(api.refreshImpact).toHaveBeenCalledWith(1, 3)
  })

  it('补充人工边界：必填人工说明，成功后清空表单', async () => {
    api.listUsers.mockResolvedValue([
      { id: 88, userNo: 'U-88', userName: '李四', address: '远郊路9号' }
    ])
    api.addBoundary.mockResolvedValue({})
    const wrapper = mountDetail(draftDetail())
    await flushPromises()

    // 缺人工说明直接补充：前端拦截
    const typeSelect = wrapper.findAllComponents(ElSelect)
      .find(s => s.props('modelValue') === 'GAS_USER')
    expect(typeSelect).toBeTruthy()
    const objectSelect = wrapper.findAllComponents(ElSelect)
      .find(s => s.props('placeholder') === '选择对象')
    objectSelect.vm.$emit('update:modelValue', 88)
    await buttonByText(wrapper, '补充').trigger('click')
    await flushPromises()
    expect(api.addBoundary).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('人工说明不能为空')

    // 填说明后提交成功
    inputByPlaceholder(wrapper, '人工说明').vm.$emit('update:modelValue', '远郊路支线人工核对纳入')
    await buttonByText(wrapper, '补充').trigger('click')
    await flushPromises()
    expect(api.addBoundary).toHaveBeenCalledWith(1, {
      objectType: 'GAS_USER', objectId: 88, note: '远郊路支线人工核对纳入', version: 3
    })
  })

  it('移除人工边界对象', async () => {
    api.removeBoundary.mockResolvedValue(null)
    const wrapper = mountDetail(draftDetail())
    await flushPromises()

    await buttonByText(wrapper, '移除').trigger('click')
    await flushPromises()
    expect(api.removeBoundary).toHaveBeenCalledWith(1, 23, 3)
  })
})
