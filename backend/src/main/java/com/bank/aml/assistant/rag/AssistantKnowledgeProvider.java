package com.bank.aml.assistant.rag;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.guard.AssistantIntent;
import com.bank.aml.rag.EnterpriseLegalRetriever;
import com.bank.aml.rag.RetrievalRequest;
import com.bank.aml.rag.RetrievalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 两级知识召回：受控公开基线 + 企业法规索引；结果在模型调用前冻结进 run 快照。 */
@Component
public class AssistantKnowledgeProvider {
    private static final Logger log = LoggerFactory.getLogger(AssistantKnowledgeProvider.class);
    private final AssistantPublicKnowledgeCatalog catalog;
    private final EnterpriseLegalRetriever legalRetriever;

    public AssistantKnowledgeProvider(AssistantPublicKnowledgeCatalog catalog,
                                      EnterpriseLegalRetriever legalRetriever) {
        this.catalog = catalog;
        this.legalRetriever = legalRetriever;
    }

    public KnowledgeBundle retrieve(String query, AssistantIntent intent, Instant asOfTime) {
        LinkedHashMap<String, AssistantEvidence> evidence = new LinkedHashMap<>();
        catalog.retrieve(query, asOfTime, 4).forEach(item -> evidence.put(item.evidenceId(), item));

        String indexVersion = "unavailable";
        RetrievalResponse.Status status = RetrievalResponse.Status.INDEX_UNAVAILABLE;
        try {
            RetrievalResponse response = legalRetriever.retrieve(new RetrievalRequest(
                    query, topic(intent), asOfTime, "CN", Set.of("PUBLIC_LEGAL"), 4, 0.08));
            indexVersion = response.indexVersion();
            status = response.status();
            for (RetrievalResponse.RetrievalHit hit : response.hits()) {
                var document = hit.document();
                String id = document.evidenceId().startsWith("LEGAL-")
                        ? document.evidenceId() : "LEGAL-" + document.evidenceId();
                String summary = compact(document.articleNumber() + " " + document.content(), 800);
                evidence.putIfAbsent(id, new AssistantEvidence(id, AssistantEvidence.EvidenceType.AML_LEGAL,
                        compact(document.title(), 160), summary,
                        compact(document.documentNumber(), 160) + " | enterprise-index:" + indexVersion));
            }
        } catch (RuntimeException exception) {
            // 动态索引不可用时降级到经过审核的公开基线；不把内部异常或查询内容写入日志。
            log.warn("AI 小助企业法规索引降级 type={}", exception.getClass().getSimpleName());
        }
        return new KnowledgeBundle(catalog.version() + "+" + safe(indexVersion), status,
                new ArrayList<>(evidence.values()));
    }

    private String topic(AssistantIntent intent) {
        return switch (intent) {
            case BANKING_KNOWLEDGE -> "AML KYC 银行金融公开知识";
            case CUSTOMER_ANALYSIS -> "当前客户风险分析";
            default -> "只读银行合规问答";
        };
    }

    private static String compact(String value, int max) {
        String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return safe.length() <= max ? safe : safe.substring(0, max) + "…";
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "unavailable" : value; }

    public record KnowledgeBundle(String version, RetrievalResponse.Status retrievalStatus,
                                  List<AssistantEvidence> evidence) {
        public KnowledgeBundle { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
    }
}
