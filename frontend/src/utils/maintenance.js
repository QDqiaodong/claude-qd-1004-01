// 管段检修影响评估与停复气联动 · 前端词典与展示辅助

export const MAINT_STATUS = ['待发布', '待停气', '施工中', '待验收', '待复气', '已完成']

export function maintStatusTagType(status) {
  switch (status) {
    case '待发布':
      return 'info'
    case '待停气':
      return 'warning'
    case '施工中':
      return 'primary'
    case '待验收':
      return 'warning'
    case '待复气':
      return 'warning'
    case '已完成':
      return 'success'
    default:
      return 'info'
  }
}

export function impactTypeLabel(type) {
  return {
    GATE_STATION: '门站',
    REGULATOR_STATION: '调压站',
    GAS_USER: '燃气用户',
    PIPE_SEGMENT: '管段'
  }[type] || type
}

export function impactSourceLabel(source) {
  return source === 'MANUAL' ? '人工边界' : '自动关联'
}

export function confirmStatusTagType(status) {
  if (status === '已确认') return 'success'
  if (status === '待确认') return 'warning'
  return 'info'
}

export function acceptStatusTagType(status) {
  return status === '已通过' ? 'success' : 'warning'
}

export function diffTypeLabel(t) {
  return {
    ADDED: '新增影响',
    REMOVED: '拓扑偏离',
    UNAVAILABLE: '对象失效'
  }[t] || t
}

export function diffTagType(t) {
  if (t === 'ADDED') return 'warning'
  return 'danger'
}
