import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import MaintenanceDetail from '../src/components/maintenance/MaintenanceDetail.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    getMaintenanceOrder: vi.fn(),
    recomputeMaintenance: vi.fn(),
    maintenanceAction: vi.fn(),
    acceptMaintenanceImpact: vi.fn(),
    confirmMaintenanceSafety: vi.fn(),
    updateMaintenanceOrder: vi.fn()
  }
}))

global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

global.HTMLElement.prototype.scrollIntoView = () => {}

function detailData(overrides = {}) {
  return {
    order: {
      id: 1, orderNo: 'JX-1', status: '待发布', frozen: false,
      ownerName: '负责人甲', planStartAt: '2026-10-10T08:00:00', planEndAt: null,
      remark: '阀门更换', cutOffAt: null, cutOffBy: null, restoredAt: null, restoredBy: null
    },
    impacts: [],
    segmentCount: 0, gateCount: 0, regulatorCount: 0, userCount: 0, manualCount: 0,
    pendingAcceptanceCount: 0, pendingSafetyCount: 0,
    blockCount: 0, warnCount: 0, orderLevelDiffs: [],
    ...overrides
  }
}

function mountDetail() {
  return mount(MaintenanceDetail, {
    props: { orderId: 1 },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('发布前差异提示与拦截', () => {
  it('存在 BLOCK 差异时展示红色提示且禁用发布按钮', async () => {
    api.getMaintenanceOrder.mockResolvedValue(detailData({
      blockCount: 1,
      impacts: [{
        id: 10, targetType: 'REGULATOR_STATION', targetId: 3, targetCode: 'RS-01',
        targetName: '临江调压站', source: 'AUTO', acceptanceStatus: '未验收', safetyStatus: '未确认',
        present: true, currentStatus: '停用',
        diffs: [{ level: 'BLOCK', kind: 'DISABLED', message: '关键关联对象已停用，发布前请先处理' }]
      }]
    }))
    const wrapper = mountDetail()
    await flushPromises()

    expect(wrapper.text()).toContain('存在必须处理的差异')
    expect(wrapper.text()).toContain('关键关联对象已停用')
    const publishBtn = wrapper.findAll('button').find(b => b.text().includes('发布并冻结'))
    expect(publishBtn.attributes('disabled')).toBeDefined()
    expect(api.maintenanceAction).not.toHaveBeenCalled()
  })

  it('无拦截差异时发布调用接口并携带 requestId', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    api.getMaintenanceOrder.mockResolvedValue(detailData({
      impacts: [{
        id: 11, targetType: 'PIPE_SEGMENT', targetId: 1, targetCode: 'PS-01',
        targetName: 'A → B', source: 'AUTO', acceptanceStatus: '未验收', safetyStatus: '未确认',
        present: true, currentStatus: '待修', diffs: []
      }]
    }))
    api.maintenanceAction.mockResolvedValue({ status: '待停气' })
    const wrapper = mountDetail()
    await flushPromises()

    const publishBtn = wrapper.findAll('button').find(b => b.text().includes('发布并冻结'))
    expect(publishBtn.attributes('disabled')).toBeUndefined()
    await publishBtn.trigger('click')
    await flushPromises()

    // 操作人对话框（teleport 到 body）
    const opDialog = Array.from(wrapper.element.querySelectorAll('.el-dialog'))
      .find(d => d.textContent.includes('请输入操作人'))
    const opInput = opDialog.querySelector('input')
    opInput.value = '发布人甲'
    opInput.dispatchEvent(new Event('input'))
    await flushPromises()
    opDialog.querySelector('.el-button--primary').click()
    await flushPromises()

    expect(api.maintenanceAction).toHaveBeenCalledTimes(1)
    const [orderId, action, payload] = api.maintenanceAction.mock.calls[0]
    expect(orderId).toBe(1)
    expect(action).toBe('publish')
    expect(payload.operator).toBe('发布人甲')
    expect(payload.requestId).toMatch(/^MT-PUBLISH-/)
  })
})

describe('停复气环节操作反馈', () => {
  it('待停气且未停气时开工按钮禁用', async () => {
    api.getMaintenanceOrder.mockResolvedValue(detailData({
      order: {
        id: 1, orderNo: 'JX-1', status: '待停气', frozen: true,
        ownerName: '负责人甲', planStartAt: '2026-10-10T08:00:00', planEndAt: null,
        remark: '', cutOffAt: null, cutOffBy: null, restoredAt: null, restoredBy: null
      }
    }))
    const wrapper = mountDetail()
    await flushPromises()
    const startBtn = wrapper.findAll('button').find(b => b.text().trim() === '开工')
    expect(startBtn.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('未停气禁止开工')
  })

  it('待复气阶段展示待确认户数，复气放行动作正确', async () => {
    api.getMaintenanceOrder.mockResolvedValue(detailData({
      order: {
        id: 1, orderNo: 'JX-1', status: '待复气', frozen: true,
        ownerName: '负责人甲', planStartAt: 'x', planEndAt: null, remark: '',
        cutOffAt: '2026-10-10T08:00:00', cutOffBy: '调度员甲',
        restoredAt: null, restoredBy: null
      },
      pendingAcceptanceCount: 0, pendingSafetyCount: 2
    }))
    api.maintenanceAction.mockResolvedValue({ status: '已完成' })
    const wrapper = mountDetail()
    await flushPromises()
    expect(wrapper.text()).toContain('待安全确认用户')
    expect(wrapper.text()).toContain('2')
    const restoreBtn = wrapper.findAll('button').find(b => b.text().includes('复气放行'))
    expect(restoreBtn.exists()).toBe(true)
  })

  it('待验收阶段可对管段登记验收，不通过必须填原因', async () => {
    api.getMaintenanceOrder.mockResolvedValue(detailData({
      order: {
        id: 1, orderNo: 'JX-1', status: '待验收', frozen: true,
        ownerName: '负责人甲', planStartAt: 'x', planEndAt: null, remark: '',
        cutOffAt: 't', cutOffBy: '甲', restoredAt: null, restoredBy: null
      },
      segmentCount: 1,
      impacts: [{
        id: 20, targetType: 'PIPE_SEGMENT', targetId: 1, targetCode: 'PS-01',
        targetName: 'A → B', source: 'AUTO', acceptanceStatus: '未验收', safetyStatus: '未确认',
        present: true, diffs: []
      }]
    }))
    const wrapper = mountDetail()
    await flushPromises()

    await wrapper.findAll('button').find(b => b.text() === '验收').trigger('click')
    await flushPromises()

    // 直接写响应式表单（el-radio 的 DOM 点击在 jsdom 下不稳定）
    expect(wrapper.vm.acceptVisible).toBe(true)
    wrapper.vm.acceptForm.passed = false
    wrapper.vm.acceptForm.operator = '验收员甲'
    await flushPromises()

    const clickSubmit = () => {
      const dlg = Array.from(wrapper.element.querySelectorAll('.el-dialog'))
        .find(d => d.textContent.includes('管段验收'))
      Array.from(dlg.querySelectorAll('button'))
        .find(b => b.textContent.includes('提交验收')).click()
      return flushPromises()
    }

    // 不通过但没填原因 → 前端拦截
    await clickSubmit()
    expect(api.acceptMaintenanceImpact).not.toHaveBeenCalled()

    // 填原因后提交成功
    wrapper.vm.acceptForm.note = '焊缝余高超标'
    await clickSubmit()
    expect(api.acceptMaintenanceImpact).toHaveBeenCalledTimes(1)
    const [, , payload] = api.acceptMaintenanceImpact.mock.calls[0]
    expect(payload.passed).toBe(false)
    expect(payload.note).toBe('焊缝余高超标')
    expect(payload.requestId).toMatch(/^MT-ACCEPT-/)
  })

  it('待复气阶段燃气用户可做安全确认', async () => {
    api.getMaintenanceOrder.mockResolvedValue(detailData({
      order: {
        id: 1, orderNo: 'JX-1', status: '待复气', frozen: true,
        ownerName: '负责人甲', planStartAt: 'x', planEndAt: null, remark: '',
        cutOffAt: 't', cutOffBy: '甲', restoredAt: null, restoredBy: null
      },
      userCount: 1, pendingSafetyCount: 1,
      impacts: [{
        id: 30, targetType: 'GAS_USER', targetId: 9, targetCode: 'U-01',
        targetName: '王建国', source: 'AUTO', acceptanceStatus: '未验收', safetyStatus: '未确认',
        present: true, diffs: []
      }]
    }))
    const wrapper = mountDetail()
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '安全确认').trigger('click')
    await flushPromises()
    const dlg = Array.from(wrapper.element.querySelectorAll('.el-dialog'))
      .find(d => d.textContent.includes('安全确认'))
    const input = dlg.querySelector('input')
    input.value = '安检员甲'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    dlg.querySelector('.el-button--primary').click()
    await flushPromises()
    expect(api.confirmMaintenanceSafety).toHaveBeenCalledTimes(1)
    const payload = api.confirmMaintenanceSafety.mock.calls[0][2]
    expect(payload.operator).toBe('安检员甲')
    expect(payload.requestId).toMatch(/^MT-SAFETY-/)
  })
})
