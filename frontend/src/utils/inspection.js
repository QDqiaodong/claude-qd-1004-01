// 网络重试幂等键：巡检登记 / 提交整改 / 隐患复核每次「提交动作」生成一个 requestId，
// 后端按 (租户, requestId) 去重；用户点「重试」时复用同一个 requestId，可安全回放首次结果。
export function newRequestId(prefix = 'REQ') {
  const ts = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 8)
  return `${prefix}-${ts}-${rand}`
}

export const BATCH_STATUS = ['待执行', '执行中', '待整改', '待复核', '已关闭']
export const RISK_LEVELS = ['高', '中', '低']

export function batchStatusTagType(status) {
  switch (status) {
    case '待执行':
      return 'info'
    case '执行中':
      return 'primary'
    case '待整改':
      return 'warning'
    case '待复核':
      return 'warning'
    case '已关闭':
      return 'success'
    default:
      return 'info'
  }
}

export function riskTagType(level) {
  if (level === '高') return 'danger'
  if (level === '中') return 'warning'
  return 'info'
}

export function hazardStatusTagType(status) {
  if (status === '已通过') return 'success'
  if (status === '待复核') return 'warning'
  return 'danger'
}

export function targetTypeLabel(type) {
  return {
    PIPE_SEGMENT: '管段',
    REGULATOR_STATION: '调压站',
    GAS_USER: '燃气用户'
  }[type] || type
}
