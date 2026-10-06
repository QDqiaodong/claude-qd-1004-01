import { reactive, ref } from 'vue'
import api from '../api'

/**
 * 巡检批次列表的「筛选 + 分页」状态机。
 *
 * 核心约束：
 *  - 输入批次编号/负责人、切换状态或「只看过期未执行」后，条件必须随每次请求发出，
 *    且一律从第一页重新计算；
 *  - 翻页 / 修改每页条数时，已填筛选条件原样保留（改页大小同样回到第一页）；
 *  - 慢响应不得覆盖新请求：每次加载发序号，过期响应（含切换租户前的在途请求）直接丢弃，
 *    保证列表里绝不会混入其它租户或上一轮的结果；
 *  - 查询失败时不清空用户已填条件，原因写入 error 由页面提示。
 */
export function useBatchList() {
  const rows = ref([])
  const loading = ref(false)
  const total = ref(0)
  const error = ref('')
  const query = reactive({
    status: '',
    keyword: '',
    overdueOnly: false,
    page: 1,
    size: 10
  })

  // 单调递增的请求序号：只有最后一次请求有权写回列表
  let ticket = 0

  function buildParams() {
    const keyword = (query.keyword || '').trim()
    return {
      status: query.status || undefined,
      keyword: keyword || undefined,
      overdueOnly: query.overdueOnly || undefined,
      // 后端页码从 0 开始，前端展示从 1 开始
      page: query.page - 1,
      size: query.size
    }
  }

  async function load() {
    const mine = ++ticket
    loading.value = true
    error.value = ''
    try {
      const data = await api.listBatches(buildParams())
      if (mine !== ticket) return null // 已有更新的请求在途/完成，丢弃过期响应
      rows.value = data.items || []
      total.value = data.total || 0
      return data
    } catch (e) {
      // 失败保留 query 与现有列表，仅记录原因供页面提示
      if (mine === ticket) error.value = (e && e.message) || '查询失败'
      return null
    } finally {
      if (mine === ticket) loading.value = false
    }
  }

  /** 筛选条件变化：回到第一页重新计算 */
  function search() {
    query.page = 1
    return load()
  }

  /** 翻页：筛选条件原样保留 */
  function changePage(page) {
    query.page = page
    return load()
  }

  /** 修改每页条数：回到第一页重新计算 */
  function changeSize(size) {
    query.size = size
    query.page = 1
    return load()
  }

  /** 切换租户：回到第一页重新加载（条件保留，过期响应由序号守卫丢弃） */
  function resetPage() {
    query.page = 1
    return load()
  }

  return {
    rows, loading, total, error, query,
    load, search, changePage, changeSize, resetPage
  }
}
