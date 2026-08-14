package com.bank.aml.tools;

import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.LegalDocumentSearcher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 绑定冻结快照的只读工具套件：每个工单动态创建一份，四个工具只从 {@link InvestigationSnapshot} 读取
 * 交易 / 股权 / 制裁事实，不再访问 {@code CustomerDataPort}。法规检索读取 RAG 索引（冻结了 indexVersion）。
 * <p>与评测模块 {@code AgentEvalFixtureTools} 的逐案例工具思路一致，保证 Agent 推理与 Guardrails
 * 共享同一份业务事实；同时记录工具调用轨迹（含参数校验，不落参数明文）。
 */
public class SnapshotToolSuite {

    private final InvestigationSnapshot snapshot;
    private final LegalDocumentSearcher searcher;
    private final List<ToolExecutionTrace> traces = new CopyOnWriteArrayList<>();

    public SnapshotToolSuite(InvestigationSnapshot snapshot, LegalDocumentSearcher searcher) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.searcher = Objects.requireNonNull(searcher, "searcher must not be null");
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

    @Tool("检索制裁黑名单（OFAC / 国内制裁名单），按客户姓名与证件号匹配，返回命中条目、名单类型与风险等级")
    public String checkSanctions(@P("客户姓名") String customerName, @P("客户证件号") String idCard) {
        return record("checkSanctions", () -> {
            requireSanctionIdentity(customerName, idCard);
            return SanctionTool.format(snapshot.sanctionHits());
        });
    }

    @Tool("检索反洗钱监管法规条文，返回与查询相关的法规标题、证据ID(evidenceId)与条文原文，供尽调报告引用")
    public String searchLegal(@P("法规查询关键词，如'大额交易报告'或'客户尽职调查'") String query) {
        return record("searchLegal", () -> {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("法规查询关键词不能为空");
            }
            List<LegalDoc> docs = searcher.search(query, 3);
            return LegalSearchTool.format(docs);
        });
    }

    /** 返回当前工单的工具调用轨迹（Agent 执行完成后读取） */
    public List<ToolExecutionTrace> traces() {
        return List.copyOf(traces);
    }

    private String record(String toolName, java.util.function.Supplier<String> invocation) {
        long start = System.nanoTime();
        try {
            String result = invocation.get();
            traces.add(ToolExecutionTrace.ok(toolName, elapsedMs(start)));
            return result;
        } catch (IllegalArgumentException e) {
            // 参数校验失败（客户编号/姓名/证件号不匹配或 query 为空）
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

    private void requireSanctionIdentity(String customerName, String idCard) {
        CustomerProfile c = snapshot.customer();
        boolean nameMatch = customerName != null && normalize(customerName).equals(normalize(c.name()));
        boolean idMatch = idCard != null && idCard.equals(c.idCard());
        // 制裁检索要求姓名与证件号两个可信字段都匹配，避免"同名不同证件号"也能取得制裁结果
        if (!nameMatch || !idMatch) {
            throw new IllegalArgumentException("客户姓名/证件号与当前工单快照不匹配");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static long elapsedMs(long startNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - startNanos);
        return elapsed == 0L ? 0L : Math.max(1L, elapsed / 1_000_000L);
    }
}
