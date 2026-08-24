// 工单状态 / 风险等级的共享展示元数据：消除三个视图间的重复定义，保证文案与样式一致。

export type TagType = 'info' | 'warning' | 'success' | 'danger'

export interface StatusMeta {
  text: string
  /** Element Plus Tag 类型（详情页） */
  type: TagType
  /** 状态圆点/徽标样式类（看板） */
  cls: string
  dot: string
}

export interface RiskMeta {
  text: string
  cls: string
}

export const statusMeta: Record<string, StatusMeta> = {
  PENDING: { text: '待处理', type: 'info', cls: 'st-pending', dot: '#64748b' },
  RUNNING: { text: '执行中', type: 'warning', cls: 'st-running', dot: '#e0a23a' },
  RETRY_WAIT: { text: '等待重试', type: 'warning', cls: 'st-running', dot: '#e0a23a' },
  DONE: { text: '已完成', type: 'success', cls: 'st-done', dot: '#2fa37f' },
  HOLD: { text: '转人工', type: 'danger', cls: 'st-hold', dot: '#c43d4b' },
  FAILED: { text: '失败', type: 'danger', cls: 'st-failed', dot: '#c43d4b' },
}

export const riskMeta: Record<string, RiskMeta> = {
  低风险: { text: '低风险', cls: 'rk-low' },
  中风险: { text: '中风险', cls: 'rk-mid' },
  高风险: { text: '高风险', cls: 'rk-high' },
}

/** 终态状态集合（达到即视为流程结束） */
export const TERMINAL_STATUSES = ['DONE', 'HOLD', 'FAILED'] as const
