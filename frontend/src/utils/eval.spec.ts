import { describe, expect, it } from 'vitest'
import { formatEvalRate } from './eval'

describe('formatEvalRate', () => {
  it('does not multiply an already-percent value again', () => {
    expect(formatEvalRate({ numerator: 9, denominator: 9, value: 100 })).toBe('100.0%')
    expect(formatEvalRate({ numerator: 4, denominator: 9, value: 44.444 })).toBe('44.4%')
  })

  it('renders an unavailable denominator as a dash', () => {
    expect(formatEvalRate({ numerator: 0, denominator: 0, value: null })).toBe('-')
    expect(formatEvalRate(null)).toBe('-')
  })
})
