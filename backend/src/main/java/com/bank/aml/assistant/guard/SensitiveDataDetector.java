package com.bank.aml.assistant.guard;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Collection;
import java.util.regex.Pattern;

/** 对模型输入、流式片段和最终回答共用的确定性敏感字段检测。 */
@Component
public class SensitiveDataDetector {
    private static final String REDACTED = "[敏感信息已遮蔽]";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?<![0-9])(?:[1-9][0-9]{16}[0-9Xx])(?![0-9])"),
            Pattern.compile("(?<![0-9])[1-9](?:[0-9][ -]?){11,17}[0-9](?![0-9])"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{16,}={0,2}"),
            Pattern.compile("(?i)\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
            Pattern.compile("(?i)\\b(?:sk|api)[-_][A-Za-z0-9_-]{16,}\\b")
    );

    public boolean containsSensitiveData(String value) {
        return containsSensitiveData(value, List.of());
    }

    /**
     * 仅排除服务端快照中已验证的完整证据 ID；其余内容仍按敏感数字、Token 和密钥规则检测。
     * 这避免 SHA-256 中偶然出现的长数字串被误认为账号，同时不允许任意伪造 ID 绕过检测。
     */
    public boolean containsSensitiveData(String value, Collection<String> allowedEvidenceIds) {
        if (value == null || value.isBlank()) return false;
        String inspected = value;
        if (allowedEvidenceIds != null) {
            for (String evidenceId : allowedEvidenceIds) {
                if (evidenceId != null && !evidenceId.isBlank()) {
                    inspected = inspected.replace(evidenceId, "[VERIFIED_EVIDENCE_ID]");
                }
            }
        }
        String candidate = inspected;
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(candidate).find());
    }

    public String redact(String value) {
        String redacted = value == null ? "" : value;
        for (Pattern pattern : PATTERNS) redacted = pattern.matcher(redacted).replaceAll(REDACTED);
        return redacted;
    }
}
