import { describe, expect, it } from 'vitest'
import { ApiContractError, parseAuthenticatedUser, parseRefundLedger } from './contracts'

describe('API runtime contracts', () => {
  it('accepts a known authenticated role', () => {
    expect(parseAuthenticatedUser({ username: 'alice', role: 'ANALYST' })).toEqual({
      username: 'alice',
      role: 'ANALYST',
    })
  })

  it('rejects an unknown authenticated role', () => {
    expect(() => parseAuthenticatedUser({ username: 'alice', role: 'ROOT' })).toThrow(ApiContractError)
  })

  it('rejects numeric money values at the transport boundary', () => {
    expect(() =>
      parseRefundLedger({
        originalByTransaction: {},
        refundedByTransaction: {},
        pendingRefundByTransaction: {},
        totalOriginal: 10,
        totalRefunded: '0.00',
        totalPendingRefund: '0.00',
        totalRetained: '10.00',
        currency: 'CNY',
        overAllocations: {},
      }),
    ).toThrow(/金额字符串|totalOriginal/)
  })
})
