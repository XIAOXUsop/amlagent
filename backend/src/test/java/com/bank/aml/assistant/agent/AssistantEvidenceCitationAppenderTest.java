package com.bank.aml.assistant.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantEvidenceCitationAppenderTest {

    @Test
    void appendsOnlyUniqueEvidenceFromSuccessfulTools() {
        String tx = "TRANSACTION_AGGREGATE:" + "a".repeat(64);
        String legal = "LEGAL-f3bf684e96f0f276";
        String result = AssistantEvidenceCitationAppender.appendMissing("结论", List.of(
                new AssistantToolTrace(1, "tx", "SUCCESS", 1, "digest", List.of(tx), null),
                new AssistantToolTrace(2, "evidence", "SUCCESS", 1, "digest", List.of(tx, legal), null),
                new AssistantToolTrace(3, "invalid", "INVALID_ARGUMENT", 1, null,
                        List.of("KB-MUST-NOT-APPEAR"), "INVALID_ARGUMENT")));

        assertThat(result).contains("结论", tx, legal).doesNotContain("KB-MUST-NOT-APPEAR");
        assertThat(result.indexOf(tx)).isEqualTo(result.lastIndexOf(tx));
    }

    @Test
    void doesNotDuplicateEvidenceAlreadyCitedByModel() {
        String evidence = "KB-OFFICIAL-TEST-001";
        String answer = "依据 " + evidence + "，仍需人工判断。";

        assertThat(AssistantEvidenceCitationAppender.appendMissing(answer, List.of(
                new AssistantToolTrace(1, "search", "SUCCESS", 1, "digest", List.of(evidence), null))))
                .isEqualTo(answer);
    }

    @Test
    void replacesModelWrittenEvidenceWithServerVerifiedToolEvidence() {
        String verified = "TRANSACTION_AGGREGATE:" + "a".repeat(64);
        String invented = "TRANSACTION_AGGREGATE:8508545810921059266deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdead";

        String result = AssistantEvidenceCitationAppender.normalizeAndAppend(
                "事实引用 `" + invented + "`。", List.of(
                        new AssistantToolTrace(1, "tx", "SUCCESS", 1, "digest", List.of(verified), null)));

        assertThat(result).doesNotContain(invented).contains(verified, "证据引用（本次成功工具调用）");
    }
}
