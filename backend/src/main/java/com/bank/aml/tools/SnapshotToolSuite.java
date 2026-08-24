package com.bank.aml.tools;

import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 绑定冻结快照的只读工具套件：每个工单动态创建一份，四个工具只从 {@link InvestigationSnapshot} 读取
 * 交易 / 股权 / 制裁事实与预检索法规证据，不再访问 {@code CustomerDataPort} 或可变 RAG 索引。
 * <p>与评测模块 {@code AgentEvalFixtureTools} 的逐案例工具思路一致，保证 Agent 推理与 Guardrails
 * 共享同一份业务事实；同时记录工具调用轨迹（含参数校验，不落参数明文）。
 */
public class SnapshotToolSuite {

    private final InvestigationSnapshot snapshot;
    private final List<ToolExecutionTrace> traces = new CopyOnWriteArrayList<>();

    public SnapshotToolSuite(InvestigationSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }

    @Tool("查询客户近180天交易画像，返回交易笔数、总额、夜间交易占比、跨境交易占比、大额交易笔数与涉及地区等风险特征")
    public String transactionProfile(@P("客户编号，如 C001") String customerId) {
        return record("transactionProfile", () -> {
            requireCustomer(customerId);
            return TransactionTool.format(snapshot.transactions(), customerId);
        });
    }

    @Tool("穿透查询企业股权结构与最终受益人(UBO)，返回股东层级、关联公司、受益所有人")
    public String corporateProfile(@P("客户编号，如 C001") String customerId) {
        return record("corporateProfile", () -> {
            requireCustomer(customerId);
            return CorporateTool.format(snapshot.shareholdings(), customerId);
        });
    }

    @Tool("读取后端已按当前客户身份完成的制裁名单筛查结果；模型无需且不得传递姓名或证件号")
    public String checkSanctions(@P("当前工单中的客户编号，如 C001") String customerId) {
        return record("checkSanctions", () -> {
            requireCustomer(customerId);
            return SanctionTool.format(snapshot.sanctionHits());
        });
    }

    @Tool("检索反洗钱监管法规条文，返回与查询相关的法规标题、证据ID(evidenceId)与条文原文，供尽调报告引用")
    public String searchLegal(@P("法规查询关键词，如'大额交易报告'或'客户尽职调查'") String query) {
        long start = System.nanoTime();
        try {
            List<String> matchedTopics = requireLegalQuery(query);
            // 从冻结快照读取预检索的法规证据，不再实时访问可变 RAG 索引
            List<LegalDoc> docs = matchedTopics.stream()
                    .flatMap(topic -> snapshot.legalEvidenceByTopic().getOrDefault(topic, List.of()).stream())
                    .distinct().toList();
            String result = LegalSearchTool.format(docs);
            // 记录结果摘要哈希与返回的 evidenceId，供报告追溯引用了哪些法规证据
            List<String> evidenceIds = docs.stream().map(LegalDoc::evidenceId).toList();
            traces.add(ToolExecutionTrace.ok("searchLegal", elapsedMs(start), digest(result), evidenceIds));
            return result;
        } catch (IllegalArgumentException e) {
            traces.add(ToolExecutionTrace.invalidArgument("searchLegal", elapsedMs(start)));
            throw e;
        } catch (RuntimeException e) {
            traces.add(ToolExecutionTrace.failed("searchLegal", elapsedMs(start), e.getClass().getSimpleName()));
            throw e;
        }
    }

    /** 返回当前工单的工具调用轨迹（Agent 执行完成后读取） */
    public List<ToolExecutionTrace> traces() {
        return List.copyOf(traces);
    }

    private String record(String toolName, java.util.function.Supplier<String> invocation) {
        long start = System.nanoTime();
        try {
            String result = invocation.get();
            traces.add(ToolExecutionTrace.ok(toolName, elapsedMs(start), digest(result), List.of()));
            return result;
        } catch (IllegalArgumentException e) {
            // 参数校验失败（客户编号不匹配或 query 为空）
            traces.add(ToolExecutionTrace.invalidArgument(toolName, elapsedMs(start)));
            throw e;
        } catch (RuntimeException e) {
            traces.add(ToolExecutionTrace.failed(toolName, elapsedMs(start), e.getClass().getSimpleName()));
            throw e;
        }
    }

    private void requireCustomer(String customerId) {
        if (customerId == null || !customerId.equals(snapshot.customer().id())) {
            throw new IllegalArgumentException("客户编号与当前工单快照不匹配");
        }
    }

    /**
     * 法规查询校验：query 必须命中快照冻结的任一法规关键词（不区分大小写），
     * 与评测工具契约一致，防止模型乱写查询词导致报告引用与工单主题无关的法规。
     */
    private List<String> requireLegalQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("法规查询关键词不能为空");
        }
        String normalized = normalize(query);
        List<String> matched = snapshot.legalKeywords().stream()
                .filter(k -> k != null && !k.isBlank())
                .filter(k -> normalized.contains(normalize(k)))
                .toList();
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("法规查询关键词与当前工单预警规则不匹配");
        }
        return matched;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static long elapsedMs(long startNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - startNanos);
        return elapsed == 0L ? 0L : Math.max(1L, elapsed / 1_000_000L);
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
