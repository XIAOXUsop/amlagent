package com.bank.aml.agent;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskFactAssembler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 尽调快照工厂：在 Agent 推理前，一次性从数据源读取客户、交易、股权、制裁原始事实并冻结，
 * 派生风险事实与 {@code sourceDigest}。Agent 工具与 Guardrails 只读该快照，不再二次访问可变数据源。
 */
@Component
public class InvestigationSnapshotFactory {

    private final CustomerDataPort dataSource;
    private final RiskFactAssembler riskFactAssembler;
    private final String legalIndexVersion;

    public InvestigationSnapshotFactory(CustomerDataPort dataSource, RiskFactAssembler riskFactAssembler,
                                        @Value("${aml.rag.legal-index-version:v1}") String legalIndexVersion) {
        this.dataSource = dataSource;
        this.riskFactAssembler = riskFactAssembler;
        this.legalIndexVersion = legalIndexVersion;
    }

    public InvestigationSnapshot create(Long caseId, int executionVersion,
                                        CustomerProfile customer, String modelRiskLevel) {
        List<TransactionRecord> transactions = dataSource.transactionsOf(customer.id());
        List<ShareholdingRecord> shareholdings = dataSource.shareholdingsOf(customer.id());
        List<SanctionRecord> sanctionHits = riskFactAssembler.searchSanctions(customer);
        RiskContext riskFacts = riskFactAssembler.assembleFrom(transactions, shareholdings, sanctionHits, modelRiskLevel);
        String sourceDigest = digest(customer, transactions, shareholdings, sanctionHits);
        return new InvestigationSnapshot(
                "case-" + caseId + "-v" + executionVersion,
                caseId, executionVersion, dataSource.asOfTime(),
                customer, transactions, shareholdings, sanctionHits,
                riskFacts, legalIndexVersion, sourceDigest);
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
