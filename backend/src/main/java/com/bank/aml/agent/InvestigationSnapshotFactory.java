package com.bank.aml.agent;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.LegalDocumentSearcher;
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

/**
 * 尽调快照工厂：在 Agent 推理前，一次性从数据源读取客户、交易、股权、制裁原始事实与法规证据并冻结，
 * 派生风险事实与 {@code sourceDigest}。Agent 工具与 Guardrails 只读该快照，不再二次访问可变数据源。
 */
@Component
public class InvestigationSnapshotFactory {

    private final CustomerDataPort dataSource;
    private final RiskFactAssembler riskFactAssembler;
    private final LegalDocumentSearcher legalSearcher;
    private final LegalKeywordResolver legalKeywordResolver;
    private final String legalIndexVersion;

    public InvestigationSnapshotFactory(CustomerDataPort dataSource, RiskFactAssembler riskFactAssembler,
                                        LegalDocumentSearcher legalSearcher,
                                        LegalKeywordResolver legalKeywordResolver,
                                        @Value("${aml.rag.legal-index-version:v1}") String legalIndexVersion) {
        this.dataSource = dataSource;
        this.riskFactAssembler = riskFactAssembler;
        this.legalSearcher = legalSearcher;
        this.legalKeywordResolver = legalKeywordResolver;
        this.legalIndexVersion = legalIndexVersion;
    }

    public InvestigationSnapshot create(Long caseId, int executionVersion,
                                        CustomerProfile customer, String modelRiskLevel, String alertRule) {
        List<TransactionRecord> transactions = dataSource.transactionsOf(customer.id());
        List<ShareholdingRecord> shareholdings = dataSource.shareholdingsOf(customer.id());
        List<SanctionRecord> sanctionHits = riskFactAssembler.searchSanctions(customer);
        // 法规证据在快照创建时预检索并冻结，Agent 工具不再实时访问可变 RAG 索引
        List<String> legalKeywords = legalKeywordResolver.resolve(alertRule);
        List<LegalDoc> legalEvidence = preloadLegalEvidence(legalKeywords);
        RiskContext riskFacts = riskFactAssembler.assembleFrom(transactions, shareholdings, sanctionHits, modelRiskLevel);
        String sourceDigest = digest(customer, transactions, shareholdings, sanctionHits);
        return new InvestigationSnapshot(
                "case-" + caseId + "-v" + executionVersion,
                caseId, executionVersion, dataSource.asOfTime(),
                customer, transactions, shareholdings, sanctionHits, legalEvidence,
                legalKeywords, riskFacts, legalIndexVersion, sourceDigest);
    }

    /** 按预警规则解析的法规关键词预检索法规证据，去重后冻结进快照 */
    private List<LegalDoc> preloadLegalEvidence(List<String> keywords) {
        List<LegalDoc> evidence = new ArrayList<>();
        for (String keyword : keywords) {
            evidence.addAll(legalSearcher.search(keyword, 3));
        }
        return evidence.stream().distinct().toList();
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
