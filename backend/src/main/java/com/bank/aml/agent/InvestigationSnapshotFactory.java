package com.bank.aml.agent;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationAlertSnapshot;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.EnterpriseLegalRetriever;
import com.bank.aml.rag.RetrievalRequest;
import com.bank.aml.rag.RetrievalResponse;
import com.bank.aml.rag.LegalIndexVersionProvider;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.service.LegalKeywordResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * 尽调快照工厂：在 Agent 推理前，一次性从数据源读取客户、交易、股权、制裁原始事实与法规证据并冻结，
 * 派生风险事实与 {@code sourceDigest}。Agent 工具与 Guardrails 只读该快照，不再二次访问可变数据源。
 */
@Component
public class InvestigationSnapshotFactory {

    private final CustomerDataPort dataSource;
    private final RiskFactAssembler riskFactAssembler;
    private final EnterpriseLegalRetriever legalRetriever;
    private final LegalKeywordResolver legalKeywordResolver;
    private final LegalIndexVersionProvider legalIndexVersions;
    private final AlertSnapshotAssembler alertAssembler;

    public InvestigationSnapshotFactory(CustomerDataPort dataSource, RiskFactAssembler riskFactAssembler,
                                        EnterpriseLegalRetriever legalRetriever,
                                        LegalKeywordResolver legalKeywordResolver,
                                        LegalIndexVersionProvider legalIndexVersions,
                                        AlertSnapshotAssembler alertAssembler) {
        this.dataSource = dataSource;
        this.riskFactAssembler = riskFactAssembler;
        this.legalRetriever = legalRetriever;
        this.legalKeywordResolver = legalKeywordResolver;
        this.legalIndexVersions = legalIndexVersions;
        this.alertAssembler = alertAssembler;
    }

    /**
     * 生产规范入口：Worker 抢占案件后冻结的全部 LINKED 预警必须传入，
     * 模型上下文、法规主题与预警摘要都基于这份集合；版本 1 不允许回退到预警文本。
     */
    public InvestigationSnapshot create(Long caseId, int executionVersion,
                                        CustomerProfile customer, String modelRiskLevel,
                                        List<InvestigationAlertSnapshot> alerts) {
        List<TransactionRecord> transactions = dataSource.transactionsOf(customer.id());
        List<ShareholdingRecord> shareholdings = dataSource.shareholdingsOf(customer.id());
        List<SanctionRecord> sanctionHits = riskFactAssembler.searchSanctions(customer);
        RiskContext riskFacts = riskFactAssembler.assembleFrom(
                transactions, shareholdings, sanctionHits, modelRiskLevel);
        // 同时依据冻结预警命中原因/场景和确定性风险事实预检索；规则可能补入的高影响动作必须先有法规证据支撑。
        List<String> legalKeywords = legalKeywordResolver.resolve(alerts, riskFacts);
        Instant asOfTime = dataSource.asOfTime();
        Map<String, List<LegalDoc>> legalEvidenceByTopic = preloadLegalEvidence(legalKeywords, asOfTime);
        List<LegalDoc> legalEvidence = legalEvidenceByTopic.values().stream().flatMap(List::stream).distinct().toList();
        String sourceDigest = digest(customer, transactions, shareholdings, sanctionHits);
        return new InvestigationSnapshot(
                "case-" + caseId + "-v" + executionVersion,
                caseId, executionVersion, asOfTime,
                customer, List.copyOf(alerts), transactions, shareholdings, sanctionHits,
                legalEvidence, legalEvidenceByTopic, legalKeywords, riskFacts,
                legalIndexVersions.activeVersion(), sourceDigest,
                alertAssembler.digest(alerts), InvestigationSnapshot.SCHEMA_VERSION_WITH_ALERTS);
    }

    /**
     * 兼容入口（仅限版本 0 存量路径与不绑定数据库案件的评测夹具）：
     * 只有预警文本时生成显式标记的 LEGACY 伪预警，不适用于版本 1 的生产案件。
     */
    public InvestigationSnapshot createForAlertRuleText(Long caseId, int executionVersion,
                                                        CustomerProfile customer, String modelRiskLevel,
                                                        String alertRule) {
        return create(caseId, executionVersion, customer, modelRiskLevel,
                alertAssembler.fromAlertRuleText(alertRule));
    }

    /** 兼容入口（保留既有 5 参签名，内部委托给显式的文本适配入口）。 */
    public InvestigationSnapshot create(Long caseId, int executionVersion,
                                        CustomerProfile customer, String modelRiskLevel, String alertRule) {
        return createForAlertRuleText(caseId, executionVersion, customer, modelRiskLevel, alertRule);
    }

    /** 按预警规则解析的法规关键词预检索法规证据，去重后冻结进快照 */
    private Map<String, List<LegalDoc>> preloadLegalEvidence(List<String> keywords, Instant asOfTime) {
        Map<String, List<LegalDoc>> evidence = new LinkedHashMap<>();
        for (String keyword : keywords) {
            RetrievalResponse response = legalRetriever.retrieve(new RetrievalRequest(
                    keyword, keyword, asOfTime, "CN", Set.of("PUBLIC_LEGAL"), 3, 0.04));
            if (response.status() == RetrievalResponse.Status.INDEX_UNAVAILABLE) {
                throw new IllegalStateException("法规索引不可用");
            }
            evidence.put(keyword, response.hits().stream().map(RetrievalResponse.RetrievalHit::document).toList());
        }
        return Map.copyOf(evidence);
    }

    /** 业务事实稳定摘要：对冻结的领域对象做确定性拼接后 SHA-256，用于证明 Agent 与 Guardrails 同源 */
    private String digest(CustomerProfile c, List<TransactionRecord> txns,
                          List<ShareholdingRecord> shareholdings, List<SanctionRecord> sanctions) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.id()).append('|').append(c.name()).append('|').append(c.idCard()).append('|').append(c.type());
        for (TransactionRecord t : txns) {
            sb.append('|').append(t.date()).append('|').append(t.amount().toPlainString())
                    .append('|').append(t.direction()).append('|').append(t.counterparty())
                    .append('|').append(t.country());
        }
        for (ShareholdingRecord s : shareholdings) {
            sb.append('|').append(s.holder()).append('|').append(s.holderType())
                    .append('|').append(s.ratio().toPlainString()).append('|').append(s.level());
        }
        for (SanctionRecord s : sanctions) {
            sb.append('|').append(s.name()).append('|').append(s.idCard())
                    .append('|').append(s.listType()).append('|').append(s.severity());
        }
        return sha256(sb.toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
