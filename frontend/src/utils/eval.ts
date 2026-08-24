import type { EvalRate } from '../api/client'

/** 后端 EvalRate.value 已使用 0~100 百分数口径，这里只负责格式化，禁止再次乘 100。 */
export function formatEvalRate(value: EvalRate | null | undefined): string {
  if (!value || value.value == null) return '-'
  return `${value.value.toFixed(1)}%`
}
