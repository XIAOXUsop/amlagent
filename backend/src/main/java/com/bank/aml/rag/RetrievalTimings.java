package com.bank.aml.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单线程（ThreadLocal）检索分段耗时探针，用于候选评测输出 dense/lexical/fusion/rerank/filter 各阶段耗时。
 * <p>同一线程内每次评测前 {@link #reset()}，检索完成后 {@link #drain()} 取走并清空；非评测路径不调用。</p>
 */
public final class RetrievalTimings {

    private static final ThreadLocal<Map<String, Long>> HOLDER =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private RetrievalTimings() {
    }

    public static void reset() {
        HOLDER.get().clear();
    }

    /** 累加某阶段的耗时 ms。 */
    public static void add(String phase, long elapsedMs) {
        if (elapsedMs <= 0) return;
        HOLDER.get().merge(phase, elapsedMs, Long::sum);
    }

    /** 取出并清空本线程的累计分段耗时。 */
    public static Map<String, Long> drain() {
        Map<String, Long> copy = new LinkedHashMap<>(HOLDER.get());
        HOLDER.get().clear();
        return copy;
    }
}