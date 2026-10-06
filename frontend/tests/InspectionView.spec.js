import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElPagination, ElSelect } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import InspectionView from '../src/views/InspectionView.vue'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: {
    listBatches: vi.fn(),
    listSegments: vi.fn().mockResolvedValue([]),
    listRegulators: vi.fn().mockResolvedValue([]),
    listUsers: vi.fn().mockResolvedValue([]),
    createBatch: vi.fn()
  }
}))

// el-table 依赖 ResizeObserver，jsdom 没有，补一个空实现
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const page = (items, total) => ({ items, total, page: 0, size: 10, totalPages: 1 })

function mountView() {
  return mount(InspectionView, {
    props: { tenant: 'A片区' },
    global: { plugins: [[ElementPlus, { locale: zhCn }]] }
  })
}

function searchButton(wrapper) {
  return wrapper.findAll('button').find(b => b.text() === '查询')
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listBatches.mockResolvedValue(page([], 0))
})

describe('筛选条件与查询请求', () => {
  it('输入批次编号/负责人后查询，关键字必须随请求发出（回归：query.key 笔误）', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('input[placeholder="批次编号 / 负责人"]').setValue('XJ-2026')
    await searchButton(wrapper).trigger('click')
    await flushPromises()

    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'XJ-2026', page: 0, size: 10 })
    )
  })

  it('切换状态与「只看过期未执行」都从第一页重新计算', async () => {
    const wrapper = mountView()
    await flushPromises()

    const statusSelect = wrapper.findComponent(ElSelect)
    statusSelect.vm.$emit('update:modelValue', '执行中')
    statusSelect.vm.$emit('change', '执行中')
    await flushPromises()
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '执行中', page: 0 })
    )

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '执行中', overdueOnly: true, page: 0 })
    )
  })
})

describe('翻页 / 改页大小保留筛选条件', () => {
  it('翻到下一页时关键字与状态原样保留', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[placeholder="批次编号 / 负责人"]').setValue('张三')
    await searchButton(wrapper).trigger('click')
    await flushPromises()

    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '张三', page: 1, size: 10 })
    )
  })

  it('修改每页条数回到第一页且条件不丢', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('input[placeholder="批次编号 / 负责人"]').setValue('张三')
    await searchButton(wrapper).trigger('click')
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 3)
    await flushPromises()

    wrapper.findComponent(ElPagination).vm.$emit('size-change', 20)
    await flushPromises()
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '张三', page: 0, size: 20 })
    )
  })

  it('切换片区后从第一页重新加载', async () => {
    const wrapper = mountView()
    await flushPromises()
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2)
    await flushPromises()

    await wrapper.setProps({ tenant: 'B片区' })
    await flushPromises()
    expect(api.listBatches).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0 }))
  })
})

describe('空结果与异常反馈', () => {
  it('空结果渲染空列表而不是报错', async () => {
    api.listBatches.mockResolvedValue(page([], 0))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无数据')
    expect(api.listBatches).toHaveBeenCalled()
  })

  it('查询失败时保留已填条件并提示原因', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => {})
    const wrapper = mountView()
    await flushPromises()

    const input = wrapper.find('input[placeholder="批次编号 / 负责人"]')
    await input.setValue('XJ-2026')
    api.listBatches.mockRejectedValueOnce(new Error('服务异常：连接超时'))
    await searchButton(wrapper).trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('服务异常：连接超时')
    // 已填条件不清空，用户可直接重试
    expect(input.element.value).toBe('XJ-2026')
    errorSpy.mockRestore()
  })
})
