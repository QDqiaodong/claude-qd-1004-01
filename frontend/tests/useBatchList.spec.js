import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useBatchList } from '../src/utils/useBatchList'
import api from '../src/api'

vi.mock('../src/api', () => ({
  default: { listBatches: vi.fn() }
}))

/** 可手动控制 resolve/reject 的 Promise，用于模拟在途慢请求 */
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const page = (items, total) => ({ items, total, page: 0, size: 10, totalPages: 1 })

beforeEach(() => {
  vi.clearAllMocks()
  api.listBatches.mockResolvedValue(page([], 0))
})

describe('筛选条件随请求发出', () => {
  it('批次编号/负责人关键字必须作为 keyword 参数发出（回归：query.key 笔误导致筛选丢失）', async () => {
    const { query, search } = useBatchList()
    query.keyword = 'XJ-2026'
    await search()
    expect(api.listBatches).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: 'XJ-2026', page: 0, size: 10 })
    )
  })

  it('空白关键字等价于不筛选，状态与只看过期按勾选发出', async () => {
    const { query, search } = useBatchList()
    query.keyword = '   '
    query.status = '执行中'
    query.overdueOnly = true
    await search()
    const params = api.listBatches.mock.calls[0][0]
    expect(params.keyword).toBeUndefined()
    expect(params.status).toBe('执行中')
    expect(params.overdueOnly).toBe(true)
  })
})

describe('翻页 / 改页大小时筛选条件保留', () => {
  it('翻页保留关键字与状态，页码转换为后端 0 基', async () => {
    const { query, search, changePage } = useBatchList()
    query.keyword = '张三'
    query.status = '待执行'
    await search()
    await changePage(3)
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: '张三', status: '待执行', page: 2, size: 10 })
    )
  })

  it('修改每页条数回到第一页重新计算且条件不丢', async () => {
    const { query, search, changePage, changeSize } = useBatchList()
    query.keyword = 'XJ'
    await search()
    await changePage(3)
    await changeSize(20)
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'XJ', page: 0, size: 20 })
    )
    expect(query.page).toBe(1)
  })

  it('任何筛选变化都从第一页重新计算', async () => {
    const { query, changePage, search } = useBatchList()
    await changePage(4)
    query.status = '已关闭'
    await search()
    expect(api.listBatches).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: '已关闭', page: 0 })
    )
    expect(query.page).toBe(1)
  })

  it('切换租户回到第一页重新加载', async () => {
    const { query, changePage, resetPage } = useBatchList()
    await changePage(5)
    await resetPage()
    expect(query.page).toBe(1)
    expect(api.listBatches).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0 }))
  })
})

describe('过期响应不得混入列表（含跨租户竞态）', () => {
  it('慢的旧响应被丢弃，列表只展示最新一次请求的结果', async () => {
    const first = deferred()
    const second = deferred()
    api.listBatches.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const { rows, total, query, load, search } = useBatchList()
    const p1 = load()                 // 旧条件发起的慢请求
    query.keyword = 'XJ-NEW'
    const p2 = search()               // 新条件发起的快请求

    second.resolve(page([{ id: 2, batchNo: 'XJ-NEW' }], 1))
    await p2
    expect(rows.value.map(r => r.batchNo)).toEqual(['XJ-NEW'])

    first.resolve(page([{ id: 1, batchNo: 'STALE' }], 1))
    await p1
    // 旧响应后到也不得覆盖
    expect(rows.value.map(r => r.batchNo)).toEqual(['XJ-NEW'])
    expect(total.value).toBe(1)
  })

  it('切换租户前的在途响应不得覆盖新租户列表', async () => {
    const tenantA = deferred()
    const tenantB = deferred()
    api.listBatches.mockReturnValueOnce(tenantA.promise).mockReturnValueOnce(tenantB.promise)

    const { rows, resetPage } = useBatchList()
    const pA = resetPage()            // 模拟 A 租户下发出的请求
    const pB = resetPage()            // 切到 B 租户后立即重新加载

    tenantB.resolve(page([{ id: 9, batchNo: 'B-批次' }], 1))
    await pB
    tenantA.resolve(page([{ id: 1, batchNo: 'A-批次' }], 1))
    await pA
    expect(rows.value.map(r => r.batchNo)).toEqual(['B-批次'])
  })
})

describe('查询失败与空结果', () => {
  it('失败时保留用户已填条件并给出原因，列表不被清空', async () => {
    const { rows, query, error, search } = useBatchList()
    api.listBatches.mockResolvedValueOnce(page([{ id: 1, batchNo: 'XJ-1' }], 1))
    await search()
    expect(rows.value).toHaveLength(1)

    query.keyword = '张三'
    query.status = '待执行'
    api.listBatches.mockRejectedValueOnce(new Error('网络超时'))
    await search()

    expect(error.value).toBe('网络超时')
    expect(query.keyword).toBe('张三')
    expect(query.status).toBe('待执行')
    expect(rows.value).toHaveLength(1) // 已展示数据不丢
  })

  it('空结果正常渲染为空列表', async () => {
    api.listBatches.mockResolvedValueOnce(page([], 0))
    const { rows, total, error, search } = useBatchList()
    await search()
    expect(rows.value).toEqual([])
    expect(total.value).toBe(0)
    expect(error.value).toBe('')
  })
})
