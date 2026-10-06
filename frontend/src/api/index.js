import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 多租户：城市燃气按片区（A片区 / B片区）隔离数据。租户通过 X-Tenant 请求头传递给后端，
// 后端用 @TenantId 自动给每条 SQL 加上租户条件，业务代码不用手写 where tenant_id。
let activeTenant = 'A片区'
http.defaults.headers.common['X-Tenant'] = activeTenant

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export function setTenant(t) {
  activeTenant = t || 'A片区'
  http.defaults.headers.common['X-Tenant'] = activeTenant
}

export default {
  setTenant,
  currentTenant: () => call(http.get('/gas-users/current-tenant')),

  // ---------------- 门站 ----------------
  listGateStations: (p) => call(http.get('/gate-stations', params(p))),
  createGateStation: (d) => call(http.post('/gate-stations', d)),
  updateGateStation: (id, d) => call(http.put(`/gate-stations/${id}`, d)),
  transferGateStation: (id, target) =>
    call(http.post(`/gate-stations/${id}/transfer`, null, params({ target }))),

  // ---------------- 调压站 ----------------
  listRegulators: (p) => call(http.get('/regulator-stations', params(p))),
  createRegulator: (d) => call(http.post('/regulator-stations', d)),
  setOutlet: (id, outletMpa) =>
    call(http.post(`/regulator-stations/${id}/outlet`, null, params({ outletMpa }))),
  inspectRegulator: (id) => call(http.post(`/regulator-stations/${id}/inspect`)),
  transferRegulator: (id, target) =>
    call(http.post(`/regulator-stations/${id}/transfer`, null, params({ target }))),

  // ---------------- 管网管段 ----------------
  listSegments: (p) => call(http.get('/pipe-segments', params(p))),
  createSegment: (d) => call(http.post('/pipe-segments', d)),
  patrolSegment: (id) => call(http.post(`/pipe-segments/${id}/patrol`)),
  transferSegment: (id, target) =>
    call(http.post(`/pipe-segments/${id}/transfer`, null, params({ target }))),

  // ---------------- 燃气用户 ----------------
  listUsers: (p) => call(http.get('/gas-users', params(p))),
  createUser: (d) => call(http.post('/gas-users', d)),
  readMeter: (id, reading) =>
    call(http.post(`/gas-users/${id}/read-meter`, null, params({ reading }))),
  checkUser: (id, result) =>
    call(http.post(`/gas-users/${id}/check`, null, params({ result }))),
  transferUser: (id, target) =>
    call(http.post(`/gas-users/${id}/transfer`, null, params({ target }))),

  // ---------------- 安全巡检批次与隐患闭环 ----------------
  listBatches: (p) => call(http.get('/inspection-batches', params(p))),
  getBatch: (id) => call(http.get(`/inspection-batches/${id}`)),
  createBatch: (d) => call(http.post('/inspection-batches', d)),
  batchAction: (id, action, operator) =>
    call(http.post(`/inspection-batches/${id}/${action}`, { operator })),
  inspectTarget: (batchId, linkId, d) =>
    call(http.post(`/inspection-batches/${batchId}/targets/${linkId}/inspect`, d)),
  rectifyHazard: (id, d) => call(http.post(`/hazards/${id}/rectify`, d)),
  reviewHazard: (id, d) => call(http.post(`/hazards/${id}/review`, d)),

  // ---------------- 管段检修影响评估与停复气联动 ----------------
  listMaintenanceOrders: (p) => call(http.get('/maintenance-orders', params(p))),
  getMaintenanceOrder: (id) => call(http.get(`/maintenance-orders/${id}`)),
  createMaintenanceOrder: (d) => call(http.post('/maintenance-orders', d)),
  updateMaintenanceOrder: (id, d) => call(http.put(`/maintenance-orders/${id}`, d)),
  recomputeMaintenance: (id) => call(http.post(`/maintenance-orders/${id}/recompute`)),
  maintenanceAction: (id, action, d) =>
    call(http.post(`/maintenance-orders/${id}/${action}`, d)),
  acceptMaintenanceImpact: (id, impactId, d) =>
    call(http.post(`/maintenance-orders/${id}/impacts/${impactId}/accept`, d)),
  confirmMaintenanceSafety: (id, impactId, d) =>
    call(http.post(`/maintenance-orders/${id}/impacts/${impactId}/safety`, d))
}
