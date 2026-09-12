package com.bank.aml.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 分析员提交的证据元数据；服务端验证材料覆盖、来源类型和 SHA-256。 */
public record EnhancedDueDiligenceEvidenceSubmission(@NotBlank @Size(max = 64) String requiredItemCode,
        @NotBlank @Size(max = 64) String sourceSystem, @NotBlank @Size(max = 128) String sourceReference,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String contentSha256) {
}
