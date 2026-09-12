import type { EnhancedDueDiligenceRequest, EvidenceStance, InvestigationEvidenceType } from '../api/client'

export const investigationScenarioText: Record<string, string> = {
  STRUCTURING: '拆分交易规避监测',
  RAPID_MOVEMENT: '资金快进快出',
  CROSS_BORDER_ANOMALY: '异常跨境交易',
  PROFILE_MISMATCH: '交易与客户画像不匹配',
  COMPLEX_OWNERSHIP: '复杂受益所有权',
  SANCTIONS_WATCHLIST: '名单身份核验',
}

export const evidenceTypeText: Record<InvestigationEvidenceType, string> = {
  TRANSACTION: '交易记录',
  CUSTOMER_PROFILE: '客户画像',
  BENEFICIAL_OWNERSHIP: '受益所有权',
  SANCTIONS_SCREENING: '名单筛查',
  DOCUMENT: '业务凭证',
  EXTERNAL_DATA: '外部数据',
  LEGAL: '法规依据',
}

export const evidenceStanceText: Record<EvidenceStance, string> = {
  SUPPORTS: '支持假设',
  CONTRADICTS: '反向证据',
}

export const hypothesisStatusText = { OPEN: '待研判', CONFIRMED: '已确认', REJECTED: '已排除' }
export const coverageConclusionText = { PENDING: '待覆盖', SUSPICIOUS: '支持可疑', EXPLAINED: '已有合理解释' }
export const operationsPriorityText = { CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', NORMAL: '常规' }
export const operationsPhaseText = {
  INVESTIGATION: '案件调查',
  REVIEW: '人工复核',
  ENHANCED_DUE_DILIGENCE: '补充尽调',
  REPORTING: '可疑报告报送',
  COMPLETED: '已完成',
}

export function formatAmount(value: string | number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value ?? 0))
}

export const reviewReasonText: Record<string, string> = {
  TRANSACTION_PATTERN_INCONSISTENT: '交易模式与客户画像不一致',
  SANCTIONS_OR_WATCHLIST_MATCH: '制裁或关注名单命中',
  SOURCE_OF_FUNDS_UNCLEAR: '资金来源或用途不清',
  CUSTOMER_DUE_DILIGENCE_CONCERN: '客户尽调信息存在疑点',
  VERIFIED_LEGITIMATE_PURPOSE: '已核实合理交易目的',
  CUSTOMER_PROFILE_CONSISTENT: '交易与客户画像一致',
  DUPLICATE_OR_KNOWN_ACTIVITY: '重复预警或已知正常活动',
  WATCHLIST_FALSE_POSITIVE: '名单同名或身份误匹配',
  MISSING_CUSTOMER_INFORMATION: '客户资料缺失',
  SOURCE_OF_FUNDS_EVIDENCE_REQUIRED: '需补充资金来源证明',
  BENEFICIAL_OWNER_VERIFICATION_REQUIRED: '需核实受益所有人',
  WATCHLIST_IDENTITY_VERIFICATION_REQUIRED: '需补充名单身份核验材料',
}

export function screeningTagType(decision: string): 'danger' | 'warning' | 'info' {
  if (decision === 'CONFIRMED') return 'danger'
  if (decision === 'REVIEW_REQUIRED') return 'warning'
  return 'info'
}

export function screeningDecisionText(decision: string): string {
  if (decision === 'CONFIRMED') return '确定命中'
  if (decision === 'REVIEW_REQUIRED') return '待人工核验'
  return '已排除'
}

export function legalTitle(text: string): string {
  return text.split('\n')[0].replace(/^【|】$/g, '')
}

export function legalBody(text: string): string {
  const index = text.indexOf('】')
  return index >= 0 ? text.slice(index + 1) : text
}

export function snapPrefix(id: string | null): string {
  return id && id.length > 12 ? `${id.slice(0, 12)}…` : (id ?? '-')
}

export const reviewDispositionText: Record<string, string> = {
  CONFIRM_SUSPICIOUS: '确认可疑',
  EXCLUDE_FALSE_POSITIVE: '排除预警',
  REQUEST_ENHANCED_DUE_DILIGENCE: '补充尽调',
}

export const legacyReviewDecisionText: Record<string, string> = {
  APPROVE: '历史决定：批准',
  REJECT: '历史决定：驳回',
  ESCALATE: '历史决定：升级',
}

export function reviewDecisionLabel(decision: string): string {
  return reviewDispositionText[decision] ?? legacyReviewDecisionText[decision] ?? decision
}

export const eddRequiredItemText: Record<string, string> = {
  CUSTOMER_IDENTITY: '客户身份及有效证件',
  BENEFICIAL_OWNER: '受益所有人及控制关系',
  SOURCE_OF_FUNDS: '资金来源证明',
  TRANSACTION_PURPOSE: '交易目的说明',
  COUNTERPARTY_RELATIONSHIP: '交易对手关系说明',
  SUPPORTING_CONTRACT_INVOICE: '合同、发票等业务凭证',
  WATCHLIST_IDENTITY: '名单身份核验材料',
}

export const eddStatusText: Record<string, string> = {
  OPEN: '待补充',
  SUBMITTED: '已提交待复核',
  RESOLVED: '已完成',
  CANCELLED: '已撤销',
}

export const reportStatusText: Record<string, string> = {
  PENDING_SUBMISSION: '待报送',
  SUBMITTED: '已报送',
  RETURNED_FOR_CORRECTION: '退回补正',
}

export const eddSourceSystemOptions = [
  { value: 'KYC_PLATFORM', label: 'KYC 平台' },
  { value: 'CORE_BANKING', label: '核心银行系统' },
  { value: 'DOCUMENT_MANAGEMENT', label: '文档管理系统' },
  { value: 'SANCTIONS_SCREENING', label: '名单筛查系统' },
  { value: 'CUSTOMER_PROVIDED', label: '客户提供' },
]

export function formatEddRequiredItems(request: EnhancedDueDiligenceRequest): string {
  return request.requiredItems.map((item) => eddRequiredItemText[item] ?? item).join('、')
}
