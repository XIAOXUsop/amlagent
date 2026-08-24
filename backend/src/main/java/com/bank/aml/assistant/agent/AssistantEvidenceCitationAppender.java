package com.bank.aml.assistant.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** 将实际成功工具调用返回的证据 ID 确定性补入最终回答，避免模型漏引或伪造引用。 */
public final class AssistantEvidenceCitationAppender {
    private static final Pattern MODEL_EVIDENCE_TOKEN = Pattern.compile(
            "\\b(?:[A-Z_]+:[A-Za-z0-9]{32,80}|(?:KB|LEGAL)-[A-Za-z0-9_-]{6,80})\\b");

    private AssistantEvidenceCitationAppender() {}

    public static String appendMissing(String answer, List<AssistantToolTrace> traces) {
        String content = answer == null ? "" : answer;
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        if (traces != null) {
            traces.stream()
                    .filter(trace -> "SUCCESS".equals(trace.status()))
                    .flatMap(trace -> trace.evidenceIds().stream())
                    .filter(id -> id != null && !id.isBlank() && !content.contains(id))
                    .forEach(missing::add);
        }
        if (missing.isEmpty()) return content;
        // 保持模型原文前缀逐字不变，使新增引用既可安全追加到 SSE，也与最终落库正文一致。
        StringBuilder result = new StringBuilder(content);
        result.append("\n\n证据引用（本次成功工具调用）：");
        missing.forEach(id -> result.append("\n- `").append(id).append('`'));
        return result.toString();
    }

    /** 删除模型自行书写（可能拼错/伪造）的引用，只保留服务端成功工具轨迹重建的证据集合。 */
    public static String normalizeAndAppend(String answer, List<AssistantToolTrace> traces) {
        String content = answer == null ? "" : MODEL_EVIDENCE_TOKEN.matcher(answer).replaceAll("");
        content = content.replace("``", "");
        return appendMissing(content, traces);
    }
}
