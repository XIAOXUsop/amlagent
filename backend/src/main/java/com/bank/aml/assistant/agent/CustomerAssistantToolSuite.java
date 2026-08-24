package com.bank.aml.assistant.agent;

import com.bank.aml.assistant.domain.AssistantDigests;
import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 绑定单次 run 快照的只读工具；客户工具刻意不接受 customerId。 */
public class CustomerAssistantToolSuite {
    private final CustomerAssistantSnapshot snapshot;
    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();
    private final List<AssistantToolTrace> traces = new CopyOnWriteArrayList<>();

    public CustomerAssistantToolSuite(CustomerAssistantSnapshot snapshot, ObjectMapper objectMapper) {
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Tool("读取当前会话绑定客户的最小化基本画像；不包含姓名、身份证、账户或数据库主键")
    public String getCurrentCustomerSummary() {
        List<String> evidenceIds = evidenceIds(AssistantEvidence.EvidenceType.CUSTOMER_PROFILE);
        return execute("getCurrentCustomerSummary", () -> success(snapshot.customer(), evidenceIds), evidenceIds);
    }

    @Tool("读取当前会话绑定客户近180天的交易聚合与异常比例；不返回交易对手明文")
    public String getCurrentTransactionRiskProfile() {
        List<String> evidenceIds = evidenceIds(AssistantEvidence.EvidenceType.TRANSACTION_AGGREGATE);
        return execute("getCurrentTransactionRiskProfile", () -> success(snapshot.transactionRisk(), evidenceIds), evidenceIds);
    }

    @Tool("读取当前会话绑定客户的脱敏股权关系与受益所有人风险")
    public String getCurrentOwnershipRiskSummary() {
        List<String> evidenceIds = evidenceIds(AssistantEvidence.EvidenceType.OWNERSHIP);
        return execute("getCurrentOwnershipRiskSummary", () -> success(snapshot.ownershipRisk(), evidenceIds), evidenceIds);
    }

    @Tool("读取当前会话绑定客户的制裁筛查状态、原因等级和名单类型；不返回身份明文")
    public String getCurrentSanctionRiskSummary() {
        List<String> evidenceIds = evidenceIds(AssistantEvidence.EvidenceType.SANCTION);
        return execute("getCurrentSanctionRiskSummary", () -> success(snapshot.sanctionRisk(), evidenceIds), evidenceIds);
    }

    @Tool("读取本次冻结快照中指定 evidenceId 的安全证据摘要")
    public String getCurrentEvidence(@P("必须是前述工具返回的 evidenceId") String evidenceId) {
        return execute("getCurrentEvidence", () -> snapshot.evidence().stream()
                        .filter(item -> item.evidenceId().equals(evidenceId))
                        .findFirst().map(item -> success(item, List.of(item.evidenceId())))
                        .orElseThrow(() -> new IllegalArgumentException("证据不属于当前快照")),
                evidenceId == null ? List.of() : List.of(evidenceId));
    }

    @Tool("在本次 run 预检索并冻结的 AML 法规证据中匹配查询")
    public String searchAmlKnowledge(@P("AML/KYC 查询关键词") String query) {
        return searchFrozen("searchAmlKnowledge", query, AssistantEvidence.EvidenceType.AML_LEGAL);
    }

    @Tool("在本次 run 预检索并冻结的公开银行金融证据中匹配查询")
    public String searchBankingKnowledge(@P("银行金融查询关键词") String query) {
        return searchFrozen("searchBankingKnowledge", query, AssistantEvidence.EvidenceType.BANKING_PUBLIC);
    }

    public List<AssistantToolTrace> traces() { return List.copyOf(traces); }

    private String searchFrozen(String toolName, String query, AssistantEvidence.EvidenceType type) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("查询关键词不能为空");
        String normalized = normalize(query);
        List<AssistantEvidence> matches = snapshot.evidence().stream()
                .filter(item -> item.type() == type)
                .map(item -> new ScoredEvidence(item, lexicalScore(normalized, normalize(item.title() + item.summary()))))
                .filter(item -> item.score() > 0)
                .sorted(java.util.Comparator.comparingInt(ScoredEvidence::score).reversed())
                .limit(3)
                .map(ScoredEvidence::evidence)
                .toList();
        List<String> evidenceIds = matches.stream().map(AssistantEvidence::evidenceId).toList();
        return execute(toolName, () -> success(matches, evidenceIds), evidenceIds);
    }

    /**
     * 工具异常只向模型返回结构化、脱敏且可恢复的信息。非法 evidenceId 仍会被
     * 服务端拒绝，但不会把一次参数失误升级为整轮模型失败。
     */
    public String recoverableError(Throwable error) {
        Throwable cause = rootCause(error);
        String errorCode = cause instanceof IllegalArgumentException ? "INVALID_ARGUMENT" : "TOOL_UNAVAILABLE";
        String message = cause instanceof IllegalArgumentException
                ? "工具参数无效。不得猜测 evidenceId；请重新调用对应摘要或检索工具，并原样使用其 evidenceIds。"
                : "工具暂时不可用。请基于已成功返回的证据回答；证据不足时明确说明当前数据不足。";
        return json(new ToolResponse(false, null, List.of(), errorCode, message));
    }

    private String execute(String toolName, Supplier<String> invocation, List<String> evidenceIds) {
        long started = System.nanoTime();
        long seq = sequence.incrementAndGet();
        try {
            String result = invocation.get();
            traces.add(new AssistantToolTrace(seq, toolName, "SUCCESS", elapsedMs(started),
                    AssistantDigests.sha256(result), evidenceIds, null));
            return result;
        } catch (IllegalArgumentException e) {
            traces.add(new AssistantToolTrace(seq, toolName, "INVALID_ARGUMENT", elapsedMs(started),
                    null, List.of(), "INVALID_ARGUMENT"));
            throw e;
        } catch (RuntimeException e) {
            traces.add(new AssistantToolTrace(seq, toolName, "FAILED", elapsedMs(started),
                    null, List.of(), e.getClass().getSimpleName()));
            throw e;
        }
    }

    private List<String> evidenceIds(AssistantEvidence.EvidenceType type) {
        return snapshot.evidence().stream().filter(item -> item.type() == type)
                .map(AssistantEvidence::evidenceId).toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具结果序列化失败", e);
        }
    }

    private String success(Object data, List<String> evidenceIds) {
        return json(new ToolResponse(true, data, evidenceIds, null, null));
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? error : current;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static int lexicalScore(String query, String document) {
        if (query.isBlank() || document.isBlank()) return 0;
        int score = document.contains(query) ? query.length() * 2 : 0;
        for (int i = 0; i + 1 < query.length(); i++) {
            String gram = query.substring(i, i + 2);
            if (document.contains(gram)) score++;
        }
        return score;
    }

    private record ScoredEvidence(AssistantEvidence evidence, int score) {}

    private record ToolResponse(boolean ok, Object data, List<String> evidenceIds,
                                String errorCode, String message) {}

    private static long elapsedMs(long started) {
        long nanos = Math.max(0, System.nanoTime() - started);
        return nanos == 0 ? 0 : Math.max(1, nanos / 1_000_000);
    }
}
