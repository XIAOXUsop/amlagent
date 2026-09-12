package com.bank.aml.assistant.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文依赖图：算出每条条目被引用了多少次。
 *
 * <p>
 * 这是"**依赖感知驱逐**"的数据来源，也是本设计与传统滑动窗口的本质区别： 驱逐顺序由**引用关系**决定，而不是由新旧决定。一条很旧但被后续结论引用的记录，
 * 会晚于一条很新但孤立的结果被驱逐。
 *
 * <p>
 * 引用计数有三类来源，缺一不可：
 * <ol>
 * <li>其他条目正文中出现的证据标识（条目之间的边）</li>
 * <li>本次快照里的实时证据（本轮工具产出的、尚未被引用的证据也必须保护）</li>
 * <li>条目自身是被引用证据的**提供者**（provider 被引用 → 该条目被引用）</li>
 * </ol>
 *
 * <p>
 * 纯函数，使用 {@link LinkedHashMap} 保证迭代顺序确定。
 */
public final class ContextDependencyGraph {

    private ContextDependencyGraph() {
    }

    /**
     * @param entries 已按 sequenceNo 升序排列的条目
     * @param liveEvidenceIds 本次 run 快照中的实时证据标识
     * @return entryId → 引用计数（未被引用者为 0）；顺序与 entries 一致
     */
    public static Map<String, Integer> refCounts(List<ContextEntry> entries, Set<String> liveEvidenceIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ContextEntry entry : entries) {
            counts.put(entry.entryId(), 0);
        }

        // 1) 条目之间的引用边。
        // 注意：**任何条目都可能是提供者**，不只 zeroLoss 类型——一条助手回答里若出现过
        // LEGAL-xxx，它同样是该证据的来源。若只把 zeroLoss 当提供者，依赖排序会退化成
        // "按体积"排序，依赖感知就名存实亡。
        Map<String, String> evidenceToProvider = new LinkedHashMap<>();
        for (ContextEntry entry : entries) {
            for (String evidenceId : entry.evidenceIds()) {
                // 同一证据被多条提及时，取序号最小的稳定归属，保证确定性
                evidenceToProvider.putIfAbsent(evidenceId, entry.entryId());
            }
        }

        for (ContextEntry consumer : entries) {
            for (String referenced : consumer.evidenceIds()) {
                String providerId = evidenceToProvider.get(referenced);
                if (providerId != null && !providerId.equals(consumer.entryId())) {
                    counts.merge(providerId, 1, Integer::sum);
                }
            }
        }

        // 2) 被本次快照实时证据引用：证据属于当前快照，其来源绝不能被当成"孤立"而优先驱逐
        for (ContextEntry entry : entries) {
            boolean referencedByLive = entry.evidenceIds().stream().anyMatch(liveEvidenceIds::contains);
            if (referencedByLive) {
                counts.merge(entry.entryId(), 1, Integer::sum);
            }
        }

        return counts;
    }

}
