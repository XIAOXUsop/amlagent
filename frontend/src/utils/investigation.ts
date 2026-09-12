import type { AlertCoverage, InvestigationHypothesis } from '../api/client'

/**
 * 预警覆盖与当前假设之间的差距类型（用于决定是否提供“重新确认”入口）。
 *
 * - PENDING：尚未形成结论（常规“形成结论”入口）。
 * - MISSING_BINDING：V26 迁移留下的存量已决覆盖，缺少可证明的假设版本绑定；
 *   需要在不反转调查判断的前提下重新确认并补齐版本。
 * - STALE_BINDING：覆盖绑定的假设版本已过期（假设被改判后未重新确认）。
 * - INCONSISTENT：覆盖结论与关联假设当前状态矛盾（例如假设已改判为排除，覆盖仍为支持可疑）。
 */
export type CoverageGap = 'PENDING' | 'MISSING_BINDING' | 'STALE_BINDING' | 'INCONSISTENT'

export type DecidedHypothesisStatus = 'CONFIRMED' | 'REJECTED'
export type DecidedCoverageConclusion = 'SUSPICIOUS' | 'EXPLAINED'

/** 判断覆盖是否需要（重新）确认；返回 null 表示当前状态一致且无需操作。 */
export function coverageGap(
  coverage: AlertCoverage | undefined,
  hypothesis: InvestigationHypothesis | undefined,
): CoverageGap | null {
  if (!coverage || !hypothesis) return null
  if (coverage.conclusion === 'PENDING') return 'PENDING'
  // 存量数据没有可证明的版本绑定：必须重新确认以补齐版本，不能默认采用当前版本
  if (coverage.hypothesisRevision == null) return 'MISSING_BINDING'
  if (coverage.hypothesisRevision !== hypothesis.revision) return 'STALE_BINDING'
  if (hypothesis.status === 'OPEN') return 'INCONSISTENT'
  const expected = expectedConclusionFor(hypothesis)
  if (expected && coverage.conclusion !== expected) return 'INCONSISTENT'
  return null
}

/** 由假设当前状态推导一致的覆盖结论；假设未决时返回 null（需先研判假设）。 */
export function expectedConclusionFor(hypothesis: InvestigationHypothesis): DecidedCoverageConclusion | null {
  if (hypothesis.status === 'CONFIRMED') return 'SUSPICIOUS'
  if (hypothesis.status === 'REJECTED') return 'EXPLAINED'
  return null
}

export function coverageGapText(gap: CoverageGap): string {
  switch (gap) {
    case 'PENDING':
      return '该预警尚未形成覆盖结论'
    case 'MISSING_BINDING':
      return '存量覆盖缺少假设版本绑定，需在不改变调查判断的前提下重新确认以补齐版本'
    case 'STALE_BINDING':
      return '覆盖依据的假设已改判，需基于最新判断依据重新确认'
    case 'INCONSISTENT':
      return '覆盖结论与关联假设当前状态不一致，重新确认后将以假设当前状态为准'
  }
}

/**
 * 判断一次提交失败是否为“调查版本冲突”（后端协议：409 + 稳定错误码）。
 * 只认协议字段；任意 409/412 不默认视为冲突，避免把其它业务错误自动刷新重试。
 */
export function isInvestigationRevisionConflict(error: unknown): boolean {
  const response = (error as { response?: { status?: number; data?: { code?: string } } } | null)?.response
  return response?.status === 409 && response?.data?.code === 'INVESTIGATION_REVISION_CONFLICT'
}

/** 假设改判时展示给用户的失效提示。 */
export const hypothesisRedecisionHint = '修改判断将递增假设版本，并使引用该假设的覆盖结论失效（需重新确认）'
