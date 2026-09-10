package com.bank.aml.assistant.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文治理器测试。全离线：不依赖模型、网络、Redis、MySQL。
 *
 * <p>这里验的不是"能不能跑"，而是**信息保全的承诺是否成立**——用户输入与证据
 * 在任何预算下都不会消失，正文压缩后仍能按图索骥取回，实在放不下时显式拒绝而非静默丢弃。
 */
class ContextGovernorTest {

    private static final String LEGAL_ID = "LEGAL-AML2024-32-7f3a91c2";

    private final ContextGovernor governor =
            new ContextGovernor(new DeterministicTokenEstimator(), new ContextCompressor(20, 10));

    // ---------- 不可驱逐 ----------

    @Test
    void userTurnsAreNeverEvictedEvenUnderExtremePressure() {
        var userTurn = entry("U1", ContextEntryKind.USER_TURN, 1, "客户 " + "很长的用户输入".repeat(40), List.of());
        var answer = entry("A1", ContextEntryKind.ASSISTANT_ANSWER, 2, "回答".repeat(60), List.of());

        var result = governor.govern(request(tinyBudget(), List.of(userTurn, answer), Set.of()));

        assertThat(result.entries()).anyMatch(e -> e.entryId().equals("U1"));
    }

    @Test
    void evidenceProvidersSurviveLongerThanUnreferencedAnswers() {
        // 序号 1 很旧，但被序号 3 引用；序号 2 很新，却无人引用
        var citedEvidence = entry("E1", ContextEntryKind.LEGAL_EVIDENCE, 1, "法规原文 " + "条文".repeat(30), List.of(LEGAL_ID));
        var orphanAnswer = entry("A2", ContextEntryKind.ASSISTANT_ANSWER, 2, "孤立回答 " + "内容".repeat(40), List.of());
        var consumer = entry("A3", ContextEntryKind.ASSISTANT_ANSWER, 3, "引用该证据的回答", List.of(LEGAL_ID));

        var result = governor.govern(request(tightBudget(), List.of(citedEvidence, orphanAnswer, consumer), Set.of(LEGAL_ID)));

        // 依赖感知：先走的是"新但孤立"的 A2，而不是"旧但被引用"的 E1
        assertThat(result.evictions()).extracting(ContextGovernor.Eviction::entryId)
                .contains("A2")
                .doesNotContain("E1");
    }

    @Test
    void extremePressureRefusesInsteadOfSilentlyDropping() {
        var hugeUserTurn = entry("U1", ContextEntryKind.USER_TURN, 1, "用户输入".repeat(500), List.of());

        var result = governor.govern(request(tinyBudget(), List.of(hugeUserTurn), Set.of()));

        // pinned 都放不下时：显式拒绝，绝不静默丢弃
        assertThat(result.refused()).isTrue();
        assertThat(result.report().degradationReasons()).contains("CONTEXT_BUDGET_EXCEEDED");
        assertThat(result.entries()).anyMatch(e -> e.entryId().equals("U1"));
    }

    // ---------- 预算充足 ----------

    @Test
    void withinBudgetNothingIsCompressedOrEvicted() {
        var userTurn = entry("U1", ContextEntryKind.USER_TURN, 1, "问题", List.of());
        var answer = entry("A1", ContextEntryKind.ASSISTANT_ANSWER, 2, "简短回答", List.of());

        var result = governor.govern(request(generousBudget(), List.of(userTurn, answer), Set.of()));

        assertThat(result.refused()).isFalse();
        assertThat(result.evictions()).isEmpty();
        assertThat(result.report().degraded()).isFalse();
        assertThat(result.entries()).hasSize(2);
    }

    // ---------- 压缩不丢引用 ----------

    @Test
    void compressionKeepsEveryEvidenceId() {
        String longAnswer = "结论：" + "推理过程".repeat(80) + " 依据 " + LEGAL_ID + " 以及 尾部结论";

        var result = governor.govern(
                request(generousBudget(), List.of(entry("A1", ContextEntryKind.ASSISTANT_ANSWER, 1, longAnswer, List.of(LEGAL_ID))), Set.of()));

        assertThat(result.report().compressedEntryIds()).contains("A1");
        assertThat(result.entries().getFirst().content()).contains(LEGAL_ID);
    }

    // ---------- 确定性 ----------

    @Test
    void sameInputProducesIdenticalResult() {
        var entries = List.of(
                entry("U1", ContextEntryKind.USER_TURN, 1, "问题一", List.of()),
                entry("A1", ContextEntryKind.ASSISTANT_ANSWER, 2, "回答一".repeat(30), List.of()),
                entry("E1", ContextEntryKind.LEGAL_EVIDENCE, 3, "证据", List.of(LEGAL_ID)));

        var first = governor.govern(request(tightBudget(), entries, Set.of(LEGAL_ID)));
        var second = governor.govern(request(tightBudget(), entries, Set.of(LEGAL_ID)));

        assertThat(first.report().contextDigest()).isEqualTo(second.report().contextDigest());
        assertThat(first.entries()).extracting(ContextEntry::entryId)
                .isEqualTo(second.entries().stream().map(ContextEntry::entryId).toList());
    }

    // ---------- 辅助 ----------

    private static ContextEntry entry(String id, ContextEntryKind kind, long seq, String content, List<String> evidenceIds) {
        int tokens = new DeterministicTokenEstimator().estimateText(content);
        return new ContextEntry(id, kind, "m-" + id, seq, content, tokens, evidenceIds,
                kind.compressible() ? "HIST-" + id.toLowerCase() : null);
    }

    private static ContextGovernor.GovernanceRequest request(ContextBudget budget,
                                                             List<ContextEntry> entries,
                                                             Set<String> liveEvidence) {
        return new ContextGovernor.GovernanceRequest("conv-1", "run-1", entries, liveEvidence, budget);
    }

    /** 可裁量预算 = 80 − 40 = 40 token：几乎必然触发驱逐 */
    private static ContextBudget tinyBudget() {
        return new ContextBudget(80, 10, 10, 10, 10, 0);
    }

    /** 可裁量预算 = 140 − 40 = 100 token：容不下全部条目，但驱逐一条后即可放下 */
    private static ContextBudget tightBudget() {
        return new ContextBudget(140, 10, 10, 10, 10, 0);
    }

    /** 可裁量预算 = 990 − 40 = 950 token：充裕 */
    private static ContextBudget generousBudget() {
        return new ContextBudget(990, 10, 10, 10, 10, 0);
    }
}
