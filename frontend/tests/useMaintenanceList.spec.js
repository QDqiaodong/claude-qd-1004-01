import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useMaintenanceList } from '../src/utils/useMaintenanceList'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: { listMaintenanceOrders: vi.fn() }
}))

function deferred() {
  let resolve
  const promise = new Promise((res) => { resolve = res })
  return { promise, resolve }
}

const page = (items, total) => ({ items, total, page: 0, size: 10, totalPages: 1 })

beforeEach(() => {
  vi.clearAllMocks()
  api.listMaintenanceOrders.mockResolvedValue(page([], 0))
})

describe('检修单列表筛选与分页', () => {
  it('编号/负责人关键字与状态随请求发出，页码转后端 0 基', async () => {
    const { query, search } = useMaintenanceList()
    query.keyword = 'JX-2026'
    query.status = '待复气'
    await search()
    expect(api.listMaintenanceOrders).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'JX-2026', status: '待复气', page: 0, size: 10 })
    )
  })

  it('空白关键字不发出；翻页保留条件；改页大小回第一页', async () => {
    const { query, search, changePage, changeSize } = useMaintenanceList()
    query.keyword = '   '
    query.status = '施工中'
    await search()
    expect(api.listMaintenanceOrders.mock.calls[0][0].keyword).toBeUndefined()

    await changePage(3)
    expect(api.listMaintenanceOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '施工中', page: 2 })
    )

    await changeSize(20)
    expect(api.listMaintenanceOrders).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '施工中', page: 0, size: 20 })
    )
  })

  it('慢响应不得覆盖新请求（含切租户前在途响应），失败时保留条件', async () => {
    const slow = deferred()
    api.listMaintenanceOrders.mockReturnValueOnce(slow.promise)
    const { rows, load, error, query } = useMaintenanceList()
    query.keyword = '旧条件'
    const first = load()
    query.keyword = '新条件'
    const second = load()
    slow.resolve(page([{ id: 1, orderNo: 'OLD' }], 1))
    await first
    await second
    // 旧响应被序号守卫丢弃，列表只认第二次（快响应）的空结果
    expect(rows.value).toEqual([])

    api.listMaintenanceOrders.mockRejectedValueOnce(new Error('网络中断'))
    query.keyword = '保留我'
    await load()
    expect(error.value).toBe('网络中断')
    expect(query.keyword).toBe('保留我')
  })
})
