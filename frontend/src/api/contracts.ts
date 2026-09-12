export class ApiContractError extends Error {
  constructor(contract: string, detail: string) {
    super(`接口响应不符合 ${contract} 契约：${detail}`)
    this.name = 'ApiContractError'
  }
}

type JsonRecord = Record<string, unknown>

function record(value: unknown, contract: string): JsonRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ApiContractError(contract, '应为 JSON 对象')
  }
  return value as JsonRecord
}

function stringField(source: JsonRecord, key: string, contract: string): string {
  if (typeof source[key] !== 'string') throw new ApiContractError(contract, `${key} 应为字符串`)
  return source[key]
}

function numberField(source: JsonRecord, key: string, contract: string): number {
  if (typeof source[key] !== 'number' || !Number.isFinite(source[key])) {
    throw new ApiContractError(contract, `${key} 应为有限数字`)
  }
  return source[key]
}

function booleanField(source: JsonRecord, key: string, contract: string): boolean {
  if (typeof source[key] !== 'boolean') throw new ApiContractError(contract, `${key} 应为布尔值`)
  return source[key]
}

function moneyMapField(source: JsonRecord, key: string, contract: string): Record<string, string> {
  const value = record(source[key], contract)
  for (const [entryKey, amount] of Object.entries(value)) {
    if (typeof amount !== 'string') throw new ApiContractError(contract, `${key}.${entryKey} 应为金额字符串`)
  }
  return value as Record<string, string>
}

export function parseAuthenticatedUser<T extends { username: string; role: string }>(value: unknown): T {
  const contract = 'AuthenticatedUser'
  const source = record(value, contract)
  const username = stringField(source, 'username', contract)
  const role = stringField(source, 'role', contract)
  if (!['ADMIN', 'REVIEWER', 'ANALYST'].includes(role)) {
    throw new ApiContractError(contract, `role 值无效：${role}`)
  }
  return { username, role } as T
}

export function parseRefundRegistration<T>(value: unknown): T {
  const contract = 'RefundRegistrationResult'
  const source = record(value, contract)
  return {
    eventId: numberField(source, 'eventId', contract),
    idempotentReplay: booleanField(source, 'idempotentReplay', contract),
    unallocatedAmount: stringField(source, 'unallocatedAmount', contract),
    currency: stringField(source, 'currency', contract),
    eventStatus: stringField(source, 'eventStatus', contract),
  } as T
}

export function parseRefundLedger<T>(value: unknown): T {
  const contract = 'RefundLedgerResult'
  const source = record(value, contract)
  return {
    originalByTransaction: moneyMapField(source, 'originalByTransaction', contract),
    refundedByTransaction: moneyMapField(source, 'refundedByTransaction', contract),
    pendingRefundByTransaction: moneyMapField(source, 'pendingRefundByTransaction', contract),
    totalOriginal: stringField(source, 'totalOriginal', contract),
    totalRefunded: stringField(source, 'totalRefunded', contract),
    totalPendingRefund: stringField(source, 'totalPendingRefund', contract),
    totalRetained: stringField(source, 'totalRetained', contract),
    currency: stringField(source, 'currency', contract),
    overAllocations: moneyMapField(source, 'overAllocations', contract),
  } as T
}
