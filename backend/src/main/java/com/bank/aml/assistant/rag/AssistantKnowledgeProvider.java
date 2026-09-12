package com.bank.aml.assistant.rag;

import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.guard.AssistantIntent;
import com.bank.aml.config.RagProperties;
import com.bank.aml.rag.EnterpriseLegalRetriever;
import com.bank.aml.rag.EvidenceSupport;
import com.bank.aml.rag.RetrievalRequest;
import com.bank.aml.rag.RetrievalResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 两级知识召回：受控公开基线 + 企业法规索引；结果在模型调用前冻结进 run 快照，并携带检索元数据。 */
@Component
public class AssistantKnowledgeProvider {

    private static final Logger log = LoggerFactory.getLogger(AssistantKnowledgeProvider.class);

    private final AssistantPublicKnowledgeCatalog catalog;

    private final EnterpriseLegalRetriever legalRetriever;

    private final int legalTopK;

    private final double legalMinRelevance;

    private final int evidenceSummaryMaxCharacters;

    private final int evidenceIdentifierMaxCharacters;

    public AssistantKnowledgeProvider(AssistantPublicKnowledgeCatalog catalog,
            EnterpriseLegalRetriever legalRetriever) {
        this(catalog, legalRetriever, new RagProperties());
    }

    @Autowired
    public AssistantKnowledgeProvider(AssistantPublicKnowledgeCatalog catalog, EnterpriseLegalRetriever legalRetriever,
            RagProperties properties) {
        this.catalog = catalog;
        this.legalRetriever = legalRetriever;
        this.legalTopK = properties.getRetrieval().getAssistantTopK();
        this.legalMinRelevance = properties.getRetrieval().getAssistantMinRelevance();
        this.evidenceSummaryMaxCharacters = properties.getRetrieval().getAssistantEvidenceSummaryMaxCharacters();
        this.evidenceIdentifierMaxCharacters = properties.getRetrieval().getAssistantEvidenceIdentifierMaxCharacters();
    }

    public KnowledgeBundle retrieve(String query, AssistantIntent intent, Instant asOfTime) {
        LinkedHashMap<String, AssistantEvidence> evidence = new LinkedHashMap<>();
        catalog.retrieve(query, asOfTime, legalTopK).forEach(item -> evidence.put(item.evidenceId(), item));

        String indexVersion = "unavailable";
        RetrievalResponse.Status status = RetrievalResponse.Status.INDEX_UNAVAILABLE;
        String traceId = "rag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Double topSupportProbability = null;
        List<String> rejectedReasons = new ArrayList<>();
        try {
            RetrievalResponse response = legalRetriever.retrieve(new RetrievalRequest(query, topic(intent), asOfTime,
                    "CN", Set.of("PUBLIC_LEGAL"), legalTopK, legalMinRelevance));
            indexVersion = response.indexVersion();
            status = response.status();
            for (RetrievalResponse.RetrievalHit hit : response.hits()) {
                var document = hit.document();
                String id = document.evidenceId().startsWith("LEGAL-") ? document.evidenceId()
                        : "LEGAL-" + document.evidenceId();
                String summary = compact(document.articleNumber() + " " + document.content(),
                        evidenceSummaryMaxCharacters);
                evidence.putIfAbsent(id,
                        new AssistantEvidence(id, AssistantEvidence.EvidenceType.AML_LEGAL,
                                compact(document.title(), evidenceIdentifierMaxCharacters), summary,
                                compact(document.documentNumber(), evidenceIdentifierMaxCharacters)
                                        + " | enterprise-index:" + indexVersion));
            }
            if (response.traces() != null && !response.traces().isEmpty()
                    && response.traces().getFirst().supportProbability() != null) {
                topSupportProbability = response.traces().getFirst().supportProbability();
            }
            rejectedReasons.addAll(rejectedReasons(response));
        }
        catch (RuntimeException exception) {
            // 动态索引不可用时降级到经过审核的公开基线；不把内部异常或查询内容写入日志。
            log.warn("AI 小助企业法规索引降级 type={}", exception.getClass().getSimpleName());
            rejectedReasons.add("法规索引暂不可用");
        }
        return new KnowledgeBundle(catalog.version() + "+" + safe(indexVersion), status,
                new ArrayList<>(evidence.values()), topSupportProbability, rejectedReasons, traceId);
    }

    private List<String> rejectedReasons(RetrievalResponse response) {
        EvidenceSupport support = response.support();
        if (support == null)
            return List.of();
        return switch (support) {
            case SUPPORTED, WEAK_SUPPORT -> List.of();
            case NO_RELEVANT_EVIDENCE -> List.of("无足够相关法规证据");
            case EVIDENCE_EXPIRED -> List.of("命中法规已失效");
            case EVIDENCE_ACCESS_DENIED -> List.of("命中法规超出当前访问范围");
            case EVIDENCE_CONFLICT -> List.of("命中条款存在规范冲突");
        };
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

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    public record KnowledgeBundle(String version, RetrievalResponse.Status retrievalStatus,
            List<AssistantEvidence> evidence, Double topSupportProbability, List<String> rejectedReasons,
            String traceId) {
        public KnowledgeBundle {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            rejectedReasons = rejectedReasons == null ? List.of() : List.copyOf(rejectedReasons);
            traceId = traceId == null ? "" : traceId;
        }
    }

}
