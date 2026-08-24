package com.bank.aml.assistant.domain;

import java.util.List;

/**
 * 一次 run 内法规检索状态（冻结进快照）：供输出决策与降级回答使用。
 * <ul>
 *   <li>{@code status}：检索结果状态（SUPPORTED / WEAK_SUPPORT 语义由上游判定，这里存 RetrievalResponse.Status 名与 support 原因）；</li>
 *   <li>{@code supportProbability}：最强命中证据的支持概率；</li>
 *   <li>{@code indexVersion}：法规索引版本身份；</li>
 *   <li>{@code queryFingerprint}：查询指纹（用于审计复核）；</li>
 *   <li>{@code rejectedReasons}：被排除证据的原因（过期/无权限/无证据/冲突等）；</li>
 *   <li>{@code traceId}：检索链路追踪 ID。</li>
 * </ul>
 */
public record RetrievalStatusView(
        String status,
        Double supportProbability,
        String indexVersion,
        String queryFingerprint,
        List<String> rejectedReasons,
        String traceId
) {
    public static final RetrievalStatusView NONE =
            new RetrievalStatusView("", null, "", "", List.of(), "");

    public RetrievalStatusView {
        rejectedReasons = rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons);
        status = status == null ? "" : status;
        indexVersion = indexVersion == null ? "" : indexVersion;
        queryFingerprint = queryFingerprint == null ? "" : queryFingerprint;
        traceId = traceId == null ? "" : traceId;
    }

    /** 法规依据不足（WEAK 或任意拒答态）；此时不得给出确定法律结论。 */
    public boolean weak() {
        return !"SUPPORTED".equals(status);
    }
}