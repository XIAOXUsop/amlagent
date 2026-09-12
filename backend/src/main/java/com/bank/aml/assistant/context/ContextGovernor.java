package com.bank.aml.assistant.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文治理器：决定每次模型调用**实际能看到什么**。
 *
 * <p>
 * 替代此前的 {@code MessageWindowChatMemory.withMaxMessages(12)} 硬截断。两者差别不在参数， 而在**信息保全的承诺**：
 * <ul>
 * <li>窗口截断：超限即静默丢弃最旧消息，被丢掉的证据在后续追问中彻底消失 → 引用校验失败 → 误判输出阻断</li>
 * <li>本治理器：用户输入永不驱逐；证据永不驱逐；正文可压缩但保留归档索引可精确取回； 实在放不下时**显式降级/拒绝**，绝不静默丢失</li>
 * </ul>
 *
 * <p>
 * 驱逐顺序是**依赖感知**的（引用少的先走），不是按时间。全部逻辑为纯函数： 无时钟、无随机、无模型调用、无 IO，同一输入必然同一输出。
 */
public final class ContextGovernor {

    private final ContextTokenEstimator estimator;

    private final ContextCompressor compressor;

    public ContextGovernor(ContextTokenEstimator estimator, ContextCompressor compressor) {
        this.estimator = estimator;
        this.compressor = compressor;
    }

    /**
     * 治理入口。流水线固定五步，不设"超限才触发"的分支——压缩是常驻协议，不是补救动作。
     */
    public GovernedContext govern(GovernanceRequest request) {
        ContextBudget budget = request.budget();
        List<ContextEntry> ordered = new ArrayList<>(request.entries());
        ordered.sort(Comparator.comparingLong(ContextEntry::sequenceNo).thenComparing(ContextEntry::entryId));

        int originalTokens = totalTokens(ordered);
        int budgetTokens = budget.discretionaryTokens();

        // 第一步：先压缩（丢细节不丢信息）
        List<ContextEntry> working = new ArrayList<>(ordered.size());
        List<String> compressReasons = new ArrayList<>();
        Map<String, ContextEntry> originalById = new LinkedHashMap<>();
        for (ContextEntry entry : ordered) {
            originalById.put(entry.entryId(), entry);
            if (!entry.kind().compressible()) {
                working.add(entry);
                continue;
            }
            var compressed = compressor.compress(entry, entry.archiveRef());
            if (compressed.truncated()) {
                working
                    .add(entry.withContent(compressed.content(), estimate(compressed.content()), entry.archiveRef()));
                compressReasons.add(entry.entryId());
            }
            else {
                working
                    .add(entry.withContent(compressed.content(), estimate(compressed.content()), entry.archiveRef()));
            }
        }

        // 第二步：仍超预算则按依赖感知顺序驱逐
        Map<String, Integer> refCounts = ContextDependencyGraph.refCounts(working, request.liveEvidenceIds());
        List<Eviction> evictions = new ArrayList<>();
        List<ContextEntry> retained = new ArrayList<>(working);
        int current = totalTokens(retained);

        if (current > budgetTokens) {
            // 候选 = 一切非 pinned 条目（用户输入除外）。
            // 零失真类型不排除在候选外，而是靠排序**排在最后**——这样在极端预算下
            // 它们仍可能被驱逐，但一定是最后才动，且驱逐前会被压缩为再水合 stub。
            List<ContextEntry> candidates = working.stream()
                .filter(e -> !e.kind().pinned())
                .sorted(evictionOrder(refCounts))
                .toList();
            for (ContextEntry candidate : candidates) {
                if (current <= budgetTokens) {
                    break;
                }
                retained.removeIf(e -> e.entryId().equals(candidate.entryId()));
                current -= candidate.tokenEstimate();
                evictions.add(new Eviction(candidate.entryId(), candidate.kind(), candidate.tokenEstimate(),
                        refCounts.getOrDefault(candidate.entryId(), 0), candidate.archiveRef()));
            }
        }

        // 第三步：若连 pinned + 证据都放不下 —— 诚实拒绝，绝不静默丢弃
        boolean degraded = !evictions.isEmpty() || !compressReasons.isEmpty();
        boolean refused = current > budgetTokens;

        ContextGovernanceReport report = new ContextGovernanceReport(estimator.name(), budgetTokens, current,
                originalTokens, retained.size(), compressReasons, evictions, refused, degraded,
                contextDigest(retained));

        return new GovernedContext(retained, evictions, report, refused);
    }

    /**
     * 驱逐排序（严格字典序，保证确定性）：
     * <ol>
     * <li>**引用计数升序** —— 没人引用的先走。这是"依赖感知"：一条很旧但被后续结论引用的记录，
     * 会晚于一条很新但孤立的结果被驱逐，与"按时间截断"本质不同</li>
     * <li>零失真靠后 —— 证据/事实类即使被驱逐也是最后动</li>
     * <li>token 降序 —— 同等条件下先驱逐体积大的，减少驱逐条数与审计噪音</li>
     * <li>序号升序 —— 最后的稳定 tie-breaker</li>
     * </ol>
     */
    private static Comparator<ContextEntry> evictionOrder(Map<String, Integer> refCounts) {
        return Comparator.comparingInt((ContextEntry e) -> refCounts.getOrDefault(e.entryId(), 0))
            .thenComparing(e -> e.kind().zeroLoss())
            .thenComparing(Comparator.comparingInt(ContextEntry::tokenEstimate).reversed())
            .thenComparingLong(ContextEntry::sequenceNo)
            .thenComparing(ContextEntry::entryId);
    }

    private int totalTokens(List<ContextEntry> entries) {
        int total = 0;
        for (ContextEntry entry : entries) {
            total += entry.tokenEstimate() + estimator.perMessageOverhead();
        }
        return total;
    }

    private int estimate(String text) {
        return estimator.estimateText(text);
    }

    /** 治理后上下文的内容指纹，用于审计复现"模型当时看到了什么" */
    private static String contextDigest(List<ContextEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ContextEntry entry : entries) {
                digest.update((entry.entryId() + "|" + entry.kind() + "|" + entry.content())
                    .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("本机不支持 SHA-256", e);
        }
    }

    // ---------- API 类型 ----------

    /** 治理输入 */
    public record GovernanceRequest(String conversationId, String runId, List<ContextEntry> entries,
            Set<String> liveEvidenceIds, ContextBudget budget) {
        public GovernanceRequest {
            entries = List.copyOf(entries);
            liveEvidenceIds = Set.copyOf(liveEvidenceIds);
        }
    }

    /** 一条被驱逐的记录（审计用，不含正文） */
    public record Eviction(String entryId, ContextEntryKind kind, int tokenEstimate, int refCount, String archiveRef) {
    }

    /** 治理结果 */
    public record GovernedContext(List<ContextEntry> entries, List<Eviction> evictions, ContextGovernanceReport report,
            boolean refused) {
        public GovernedContext {
            entries = List.copyOf(entries);
            evictions = List.copyOf(evictions);
        }
    }

    /** 治理报告：落库后可复现"模型看到了什么、没看到什么" */
    public record ContextGovernanceReport(String estimatorName, int budgetTokens, int usedTokens, int originalTokens,
            int retainedEntries, List<String> compressedEntryIds, List<Eviction> evictions, boolean refused,
            boolean degraded, String contextDigest) {
        public ContextGovernanceReport {
            compressedEntryIds = List.copyOf(compressedEntryIds);
            evictions = List.copyOf(evictions);
        }

        /** 降级原因码（排序后，供指标与日志使用，低基数） */
        public List<String> degradationReasons() {
            Set<String> reasons = new LinkedHashSet<>();
            if (!compressedEntryIds.isEmpty()) {
                reasons.add("CONTEXT_COMPRESSED");
            }
            if (!evictions.isEmpty()) {
                reasons.add("CONTEXT_EVICTED");
            }
            if (refused) {
                reasons.add("CONTEXT_BUDGET_EXCEEDED");
            }
            return List.copyOf(reasons);
        }
    }

}
