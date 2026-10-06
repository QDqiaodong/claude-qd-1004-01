// 管段检修联动：状态标签、对象类型标签、差异级别标签、操作环节定义。
// 所有写动作每次提交生成一个 requestId：后端按 (租户, requestId) 幂等回放首次结果，
// 两名操作员并发发布/复气时，后到请求直接拿到先到者的首次结果。
export function newRequestId(prefix = 'MT') {
  const ts = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 8)
  return `${prefix}-${ts}-${rand}`
}

export const MO_STATUS = ['待发布', '待停气', '施工中', '待验收', '待复气', '已完成']

export function moStatusTagType(status) {
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
      return 'danger'
    case '已完成':
      return 'success'
    default:
      return 'info'
  }
}

export function targetTypeLabel(type) {
  return {
    PIPE_SEGMENT: '管段',
    GATE_STATION: '门站',
    REGULATOR_STATION: '调压站',
    GAS_USER: '燃气用户',
    MANUAL: '人工边界'
  }[type] || type
}

export function sourceLabel(source) {
  return source === 'MANUAL' ? '人工说明' : '拓扑关联'
}

export function diffLevelTagType(level) {
  return level === 'BLOCK' ? 'danger' : 'warning'
}

export function diffKindLabel(kind) {
  return {
    DISABLED: '对象停用',
    DELETED: '档案缺失',
    CROSS_TENANT: '跨租户',
    CODE_CHANGED: '编号变更',
    NAME_CHANGED: '名称变更',
    TOPOLOGY_GONE: '拓扑脱钩',
    TOPOLOGY_EXTRA: '拓扑新增'
  }[kind] || kind
}

// 状态机各环节：key 为接口动作；按钮文案/确认语集中维护，页面按状态启用
export const MO_ACTIONS = {
  publish: { label: '发布并冻结范围', confirm: '发布将冻结影响快照；停用、跨租户或拓扑冲突会被拦截并保留编辑内容，确认发布？' },
  cutoff: { label: '登记停气', confirm: '确认已按计划完成停气操作？停气后才允许开工。' },
  'start-work': { label: '开工', confirm: '开工前必须已完成停气，确认现场已停气并开工？' },
  'finish-work': { label: '施工完成', confirm: '确认现场施工已完成，进入逐管段验收？' },
  'request-restore': { label: '申请复气', confirm: '申请复气要求所有管段均已验收通过，确认申请？' },
  restore: { label: '复气放行', confirm: '复气放行要求：全部管段验收通过 + 全部受影响用户安全确认完成，确认放行？' }
}
