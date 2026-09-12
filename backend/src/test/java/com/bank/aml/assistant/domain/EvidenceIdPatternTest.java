package com.bank.aml.assistant.domain;

import com.bank.aml.assistant.guard.AssistantOutputGuard;
import com.bank.aml.assistant.guard.SensitiveDataDetector;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据标识识别规则的测试。
 *
 * <p>
 * 存在意义：此前 {@code AssistantOutputGuard} 与 {@code AssistantEvidenceCitationAppender}
 * 各自定义正则且宽窄不一致，护栏只认 64 位小写十六进制，**扫不到**模型吐出的大写/非 64 位标识， 使伪造引用可以穿过输出校验。本测试把统一后的行为钉死。
 */
class EvidenceIdPatternTest {

    private final AssistantOutputGuard guard = new AssistantOutputGuard(new SensitiveDataDetector());

    @Test
    void matchesEveryFormTheModelMayEmit() {
        // 规范形态：64 位小写十六进制的事实 ID
        assertThat(
                matches("TRANSACTION_AGGREGATE:" + "71b39e0804a17ef7cdc8508545810921059266fbc43fdb4446d90f10c235595b"))
            .isTrue();
        // 大写 / 非 64 位：旧护栏正则扫不到，正是本次修复的目标
        assertThat(matches("CUSTOMER_PROFILE:AABBCCDDEEFF00112233445566778899")).isTrue();
        assertThat(matches("SANCTION:" + "a".repeat(80))).isTrue();
        // 知识证据命名空间（内容寻址，跨轮次稳定）
        assertThat(matches("KB-OFFICIAL-TEST-001")).isTrue();
        assertThat(matches("LEGAL-AML2024-32-7f3a91c2")).isTrue();
    }

    @Test
    void doesNotMatchOrdinaryText() {
        assertThat(matches("CURRENT_CUSTOMER")).isFalse();
        assertThat(matches("客户编号 C001")).isFalse();
        assertThat(matches("2026-08-23T20:00:00")).isFalse();
    }

    /**
     * 回归证明：这条断言在修复前会**失败**。
     *
     * <p>
     * 伪造 ID 是大写且长度不为 64，落在旧护栏正则的扫描范围之外 → {@code EVIDENCE_NOT_IN_SNAPSHOT} 不会触发 → 校验误判为通过。
     */
    @Test
    void forgedEvidenceIdInBroadFormIsNowRejected() {
        var forged = "CUSTOMER_PROFILE:AABBCCDDEEFF00112233445566778899";

        var result = guard.validate(snapshot(), "依据 " + forged + " 可直接认定。");

        assertThat(result.violations()).contains("EVIDENCE_NOT_IN_SNAPSHOT");
        assertThat(result.valid()).isFalse();
    }

    @Test
    void legitimateSnapshotEvidenceIdStillAccepted() {
        String legitimate = snapshot().evidence().getFirst().evidenceId();

        assertThat(guard.validate(snapshot(), "CURRENT_CUSTOMER 交易共2笔，证据 " + legitimate).violations())
            .doesNotContain("EVIDENCE_NOT_IN_SNAPSHOT");
    }

    private static boolean matches(String candidate) {
        return EvidenceIdPattern.PATTERN.matcher(candidate).find();
    }

    private CustomerAssistantSnapshot snapshot() {
        String id = "TRANSACTION_AGGREGATE:71b39e0804a17ef7cdc8508545810921059266fbc43fdb4446d90f10c235595b";
        return new CustomerAssistantSnapshot("s", "c", "r", Instant.EPOCH,
                new AssistantCustomerView("CURRENT_CUSTOMER", "个人", "贸易", "上海", "-", "ENABLED"),
                new TransactionRiskView(2, new BigDecimal("10"), new BigDecimal("5"), 0, 0, 0, 0, List.of("CNY"),
                        List.of("中国大陆"), true),
                new OwnershipRiskView(0, 0, List.of()), new SanctionRiskView(false, 0, 0, List.of()),
                List.of(new AssistantEvidence(id, AssistantEvidence.EvidenceType.TRANSACTION_AGGREGATE, "交易", "交易共2笔",
                        "TEST/v1"),
                        new AssistantEvidence("KB-OFFICIAL-TEST-001", AssistantEvidence.EvidenceType.AML_LEGAL, "规则",
                                "需要人工判断", "OFFICIAL")),
                "TEST", "v1", "legal-v1", RetrievalStatusView.NONE, "digest");
    }

}
