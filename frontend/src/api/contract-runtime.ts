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
  // 只按名字判断"这应该是个金额"的字段必须是纯十进制字符串。
  //
  // 这里**不含 regCapital**：注册资本在本系统里是自由文本，不是金额。
  // 数据库列是 VARCHAR(128)，DTO 与 schema 目录都把它声明成 string/nullableString，
  // 演示种子数据也写着「注册资本5000万人民币」「—」这类人工可读的值。
  // 曾经把 regCapital 按金额校验，结果是**整个客户列表被一行演示数据判为违约而丢弃**——
  // 契约检查失败会让调用方拿不到任何数据，代价远超它想防的格式漂移。
  // 要收紧注册资本的格式，应当先改数据模型，而不是在这条通用启发式里顺带卡住。
  if (/(?:amount|Amount)$/.test(key) && (typeof value !== 'string' || !DECIMAL.test(value))) {
    throw new ApiContractError(contract, `${path} 必须是十进制金额字符串`)
  }
}
