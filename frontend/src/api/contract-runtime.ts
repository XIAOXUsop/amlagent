import { ApiContractError } from './contracts'

type PrimitiveKind = 'string' | 'number' | 'boolean'

export interface FieldRule {
  kind: PrimitiveKind | 'object' | 'array' | 'record'
  optional?: boolean
  nullable?: boolean
  semanticValidation?: boolean
  enumValues?: readonly string[]
  fields?: ObjectFields
  item?: FieldRule
}

export type ObjectFields = Record<string, FieldRule>

export const string = (options: Omit<FieldRule, 'kind'> = {}): FieldRule => ({ kind: 'string', ...options })
export const number = (options: Omit<FieldRule, 'kind'> = {}): FieldRule => ({ kind: 'number', ...options })
export const boolean = (options: Omit<FieldRule, 'kind'> = {}): FieldRule => ({ kind: 'boolean', ...options })
export const object = (fields: ObjectFields, options: Omit<FieldRule, 'kind' | 'fields'> = {}): FieldRule => ({
  kind: 'object',
  fields,
  ...options,
})
export const array = (item: FieldRule, options: Omit<FieldRule, 'kind' | 'item'> = {}): FieldRule => ({
  kind: 'array',
  item,
  ...options,
})
export const record = (item: FieldRule, options: Omit<FieldRule, 'kind' | 'item'> = {}): FieldRule => ({
  kind: 'record',
  item,
  ...options,
})

const RFC_3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
const DECIMAL = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/

export function validate(value: unknown, rule: FieldRule, contract: string, path = '$'): void {
  if (value === undefined && rule.optional) return
  if (value === null && rule.nullable) return
  if (value === undefined || value === null) {
    throw new ApiContractError(contract, `${path} 缺失或不可为空`)
  }
  if (rule.kind === 'array') {
    if (!Array.isArray(value)) throw new ApiContractError(contract, `${path} 应为数组`)
    if (!rule.item) throw new ApiContractError(contract, `${path} 数组契约缺少元素规则`)
    value.forEach((item, index) => validate(item, rule.item!, contract, `${path}[${index}]`))
    return
  }
  if (rule.kind === 'object' || rule.kind === 'record') {
    if (typeof value !== 'object' || Array.isArray(value)) {
      throw new ApiContractError(contract, `${path} 应为对象`)
    }
    const source = value as Record<string, unknown>
    if (rule.kind === 'record') {
      if (!rule.item) throw new ApiContractError(contract, `${path} 映射契约缺少值规则`)
      Object.entries(source).forEach(([key, item]) => validate(item, rule.item!, contract, `${path}.${key}`))
    } else {
      Object.entries(rule.fields ?? {}).forEach(([key, field]) =>
        validate(source[key], field, contract, `${path}.${key}`),
      )
      Object.entries(source).forEach(([key, item]) => {
        if (!(key in (rule.fields ?? {}))) validateJson(item, contract, `${path}.${key}`, key)
      })
    }
    return
  }
  if (typeof value !== rule.kind || (rule.kind === 'number' && !Number.isFinite(value))) {
    throw new ApiContractError(contract, `${path} 应为${rule.kind}`)
  }
  if (rule.enumValues && !rule.enumValues.includes(value as string)) {
    throw new ApiContractError(contract, `${path} 枚举值无效`)
  }
  if (rule.semanticValidation !== false) {
    validateNamedScalar(value, contract, path, path.split('.').at(-1) ?? '')
  }
}

export function validateJson(value: unknown, contract: string, path = '$', key = ''): void {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') {
    validateNamedScalar(value, contract, path, key)
    return
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new ApiContractError(contract, `${path} 不是有限数字`)
    validateNamedScalar(value, contract, path, key)
    return
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => validateJson(item, contract, `${path}[${index}]`, key))
    return
  }
  if (typeof value === 'object') {
    Object.entries(value as Record<string, unknown>).forEach(([childKey, item]) =>
      validateJson(item, contract, `${path}.${childKey}`, childKey),
    )
    return
  }
  throw new ApiContractError(contract, `${path} 不是有效 JSON 值`)
}

function validateNamedScalar(value: unknown, contract: string, path: string, key: string): void {
  if (value === null) return
  if (/(?:At|Time|Cutoff|timestamp)$/.test(key) && (typeof value !== 'string' || !RFC_3339.test(value))) {
    throw new ApiContractError(contract, `${path} 必须是带时区的 RFC 3339 时间`)
  }
  if (/(?:amount|Amount|regCapital)$/.test(key) && (typeof value !== 'string' || !DECIMAL.test(value))) {
    throw new ApiContractError(contract, `${path} 必须是十进制金额字符串`)
  }
}
