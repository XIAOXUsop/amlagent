import { describe, expect, it } from 'vitest'
import { riskMeta, statusMeta, TERMINAL_STATUSES } from './case'

describe('case constants', () => {
  it('covers every case status with display metadata', () => {
    const statuses = ['PENDING', 'RUNNING', 'DONE', 'HOLD', 'FAILED']
    for (const s of statuses) {
      expect(statusMeta[s], `missing statusMeta for ${s}`).toBeDefined()
      expect(statusMeta[s].text).toBeTruthy()
    }
  })

  it('covers every risk level with a style class', () => {
    const levels = ['低风险', '中风险', '高风险']
    for (const l of levels) {
      expect(riskMeta[l], `missing riskMeta for ${l}`).toBeDefined()
      expect(riskMeta[l].cls).toMatch(/^rk-/)
    }
  })

  it('lists terminal statuses used to finalize the workflow UI', () => {
    expect(TERMINAL_STATUSES).toEqual(['DONE', 'HOLD', 'FAILED'])
  })
})
