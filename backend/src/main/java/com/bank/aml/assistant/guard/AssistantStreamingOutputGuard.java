package com.bank.aml.assistant.guard;

/**
 * 流式安全缓冲：始终保留尾部窗口，与下一 chunk 合并检测，
 * 防止身份证/账号被拆分为多个 token 后绕过单 chunk 检测。
 */
public class AssistantStreamingOutputGuard {
    private static final int MIN_HOLD_BACK_CHARS = 64;
    private final SensitiveDataDetector sensitiveData;
    private final java.util.List<String> allowedEvidenceIds;
    private final int holdBackChars;
    private final StringBuilder pending = new StringBuilder();
    private boolean blocked;

    public AssistantStreamingOutputGuard(SensitiveDataDetector sensitiveData) {
        this(sensitiveData, java.util.List.of());
    }

    public AssistantStreamingOutputGuard(SensitiveDataDetector sensitiveData,
                                         java.util.Collection<String> allowedEvidenceIds) {
        this.sensitiveData = sensitiveData;
        this.allowedEvidenceIds = allowedEvidenceIds == null ? java.util.List.of()
                : allowedEvidenceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        this.holdBackChars = Math.max(MIN_HOLD_BACK_CHARS,
                this.allowedEvidenceIds.stream().mapToInt(String::length).max().orElse(0));
    }

    /** 返回本次可以安全发送的前缀；高风险命中后永久返回空字符串。 */
    public synchronized String accept(String delta) {
        if (blocked || delta == null || delta.isEmpty()) return "";
        pending.append(delta);
        if (containsSensitiveDataDuringStream(pending.toString())) {
            blocked = true;
            pending.setLength(0);
            return "";
        }
        if (pending.length() <= holdBackChars) return "";
        int flushLength = pending.length() - holdBackChars;
        String safe = pending.substring(0, flushLength);
        pending.delete(0, flushLength);
        return safe;
    }

    /** 模型正常结束时释放最后的安全尾部。 */
    public synchronized String finish() {
        if (blocked) return "";
        if (sensitiveData.containsSensitiveData(pending.toString(), allowedEvidenceIds)) {
            blocked = true;
            pending.setLength(0);
            return "";
        }
        String safe = pending.toString();
        pending.setLength(0);
        return safe;
    }

    public synchronized boolean blocked() { return blocked; }

    private boolean containsSensitiveDataDuringStream(String value) {
        int unresolvedSuffixLength = longestAllowedEvidencePrefixAtEnd(value);
        String checkable = unresolvedSuffixLength == 0 ? value
                : value.substring(0, value.length() - unresolvedSuffixLength);
        return sensitiveData.containsSensitiveData(checkable, allowedEvidenceIds);
    }

    /** 不检查可能被 token/chunk 截断的合法 evidenceId 前缀；finish 时仍会严格检查不完整前缀。 */
    private int longestAllowedEvidencePrefixAtEnd(String value) {
        int longest = 0;
        for (String evidenceId : allowedEvidenceIds) {
            int max = Math.min(value.length(), evidenceId.length() - 1);
            for (int length = max; length > longest; length--) {
                if (value.endsWith(evidenceId.substring(0, length))) {
                    longest = length;
                    break;
                }
            }
        }
        return longest;
    }
}
