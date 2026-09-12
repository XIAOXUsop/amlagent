/** 将带时区的 RFC 3339 时间转换为用户当前时区；空值或非法值返回占位符。 */
export function fmtDateTime(s: string | null | undefined): string {
  if (!s) return '-'
  const instant = new Date(s)
  if (Number.isNaN(instant.getTime())) return '-'
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(instant)
}
