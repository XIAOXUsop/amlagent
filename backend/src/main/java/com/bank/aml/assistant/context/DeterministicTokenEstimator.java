package com.bank.aml.assistant.context;

/**
 * 确定性 token 估算（默认实现）：CJK 码点按 1 token，其余按 1/4 token 经验折算。
 *
 * <p>
 * 纯函数、零依赖、无时钟、无随机、无 locale 依赖（一律 `Locale.ROOT`）。 同一输入**必然**得到同一结果——这是"同输入同输出"这条硬约束的第一道保证。
 *
 * <p>
 * 保守性：中文按 1 token/字 是偏高的估计（多数分词器中文约 0.6~1.0 token/字），
 * 偏保守的后果是"略早触发压缩"，而不是"超出真实窗口"——对合规系统而言前者可接受，后者不可接受。
 */
public final class DeterministicTokenEstimator implements ContextTokenEstimator {

    /** 每条消息的固定结构开销（角色标记、分隔符等） */
    private static final int MESSAGE_OVERHEAD = 4;

    /** 非 CJK 字符的折算系数：4 字符 ≈ 1 token */
    private static final double ASCII_CHARS_PER_TOKEN = 4.0;

    @Override
    public String name() {
        return "HEURISTIC";
    }

    @Override
    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            }
            else {
                other++;
            }
            i += Character.charCount(cp);
        }
        // 向上取整，且非 CJK 部分至少 1 token（避免纯英文短串被估成 0）
        int otherTokens = other == 0 ? 0 : (int) Math.ceil(other / ASCII_CHARS_PER_TOKEN);
        return cjk + otherTokens;
    }

    @Override
    public int perMessageOverhead() {
        return MESSAGE_OVERHEAD;
    }

    /** CJK 统一表意文字 + 中文标点；这些字符普遍接近 1 token/字 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) // 基本区
                || (cp >= 0x3400 && cp <= 0x4DBF) // 扩展 A
                || (cp >= 0x3000 && cp <= 0x303F) // 中文标点
                || (cp >= 0xFF00 && cp <= 0xFFEF); // 全角字符
    }

}
