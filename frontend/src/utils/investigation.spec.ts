import { describe, expect, it } from 'vitest'
import { coverageGap, coverageGapText, expectedConclusionFor, isInvestigationRevisionConflict } from './investigation'
import type { AlertCoverage, InvestigationHypothesis } from '../api/client'

/** A1 回归：存量覆盖（NULL 版本绑定）与改判后的覆盖必须提供重新确认入口，且不要求反转调查判断。 */

function hypothesis(id: number, revision: number, status: InvestigationHypothesis['status']): InvestigationHypothesis {
  return {
    id,
    caseId: 7,
    scenarioCode: 'STRUCTURING',
    hypothesisCode: `H-${id}`,
    title: `假设${id}`,
    investigationQuestion: '是否成立？',
    requiredEvidenceTypes: [],
    status,
    rationale: '既有判断依据',
    revision,
    createdBy: 'analyst',
    updatedBy: null,
    createdAt: '',
    updatedAt: '',
    evidence: [],
  }
}

function coverage(
  alertId: number,
  hypothesisId: number,
  hypothesisRevision: number | null,
  conclusion: AlertCoverage['conclusion'],
): AlertCoverage {
  return {
    id: alertId,
    alertId,
    caseId: 7,
    hypothesisId,
    hypothesisRevision,
    conclusion,
    analysisSummary: '原覆盖分析',
    revision: 1,
    updatedBy: 'analyst',
    updatedAt: '',
  }
}

describe('coverageGap（存量覆盖重新确认门禁的页面判定）', () => {
  it('flags legacy decided coverage with null binding as MISSING_BINDING without flipping the judgment', () => {
    // V25 时代覆盖：已 SUSPICIOUS、hypothesisRevision 为 NULL，假设保持 CONFIRMED 不变
    const gap = coverageGap(coverage(11, 31, null, 'SUSPICIOUS'), hypothesis(31, 3, 'CONFIRMED'))
    expect(gap).toBe('MISSING_BINDING')
    expect(coverageGapText(gap!)).toContain('不改变调查判断')
    // 结论与假设状态一致：重新确认不要求反转
    expect(expectedConclusionFor(hypothesis(31, 3, 'CONFIRMED'))).toBe('SUSPICIOUS')
  })

  it('flags stale binding after hypothesis redecision', () => {
    const gap = coverageGap(coverage(11, 31, 2, 'SUSPICIOUS'), hypothesis(31, 3, 'CONFIRMED'))
    expect(gap).toBe('STALE_BINDING')
  })

  it('flags inconsistent conclusion when hypothesis was redecided to the opposite status', () => {
    // 版本绑定已是当前版本，但结论与改判后的假设状态矛盾
    const gap = coverageGap(coverage(11, 31, 3, 'SUSPICIOUS'), hypothesis(31, 3, 'REJECTED'))
    expect(gap).toBe('INCONSISTENT')
    expect(coverageGapText(gap!)).toContain('以假设当前状态为准')
  })

  it('treats pending coverage as the normal conclude entry', () => {
    expect(coverageGap(coverage(11, 31, null, 'PENDING'), hypothesis(31, 3, 'CONFIRMED'))).toBe('PENDING')
  })

  it('returns null when coverage is consistent with a decided hypothesis at the current revision', () => {
    expect(coverageGap(coverage(11, 31, 3, 'SUSPICIOUS'), hypothesis(31, 3, 'CONFIRMED'))).toBeNull()
    expect(coverageGap(coverage(11, 31, 3, 'EXPLAINED'), hypothesis(31, 3, 'REJECTED'))).toBeNull()
  })

  it('flags decided coverage whose hypothesis is still open at the same revision', () => {
    expect(coverageGap(coverage(11, 31, 3, 'EXPLAINED'), hypothesis(31, 3, 'OPEN'))).toBe('INCONSISTENT')
  })
})

describe('isInvestigationRevisionConflict（W1 冲突协议判定）', () => {
  const conflict = (status: number, code?: string) => ({ response: { status, data: code ? { code } : {} } })

  it('recognizes 409 + INVESTIGATION_REVISION_CONFLICT as conflict', () => {
    expect(isInvestigationRevisionConflict(conflict(409, 'INVESTIGATION_REVISION_CONFLICT'))).toBe(true)
  })

  it('does not treat other 409/412/500 errors as conflicts', () => {
    expect(isInvestigationRevisionConflict(conflict(409, 'WORKFLOW_STATE_CONFLICT'))).toBe(false)
    expect(isInvestigationRevisionConflict(conflict(409))).toBe(false)
    expect(isInvestigationRevisionConflict(conflict(412, 'PRECONDITION_FAILED'))).toBe(false)
    expect(isInvestigationRevisionConflict(conflict(500))).toBe(false)
    expect(isInvestigationRevisionConflict(new Error('network'))).toBe(false)
  })
})
