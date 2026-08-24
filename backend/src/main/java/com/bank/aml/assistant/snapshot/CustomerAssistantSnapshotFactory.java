package com.bank.aml.assistant.snapshot;

import com.bank.aml.assistant.domain.AssistantCustomerView;
import com.bank.aml.assistant.domain.AssistantDigests;
import com.bank.aml.assistant.domain.AssistantEvidence;
import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.domain.OwnershipRiskView;
import com.bank.aml.assistant.domain.SanctionRiskView;
import com.bank.aml.assistant.domain.TransactionRiskView;
import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.guard.AssistantIntent;
import com.bank.aml.assistant.rag.AssistantKnowledgeProvider;
import com.bank.aml.common.enums.RiskLevel;
import com.bank.aml.common.exception.CustomerNotFoundException;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.rag.LegalIndexVersionProvider;
import com.bank.aml.risk.RiskFactAssembler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 为一次 AI 小助 run 创建独立脱敏快照，不复用工单语义。 */
@Component
public class CustomerAssistantSnapshotFactory {
    private final CustomerRepository customers;
    private final CustomerDataPort dataSource;
    private final RiskFactAssembler riskFacts;
    private final LegalIndexVersionProvider legalIndexVersion;
    private final AssistantKnowledgeProvider knowledge;

    public CustomerAssistantSnapshotFactory(CustomerRepository customers, CustomerDataPort dataSource,
                                            RiskFactAssembler riskFacts, LegalIndexVersionProvider legalIndexVersion,
                                            AssistantKnowledgeProvider knowledge) {
        this.customers = customers;
        this.dataSource = dataSource;
        this.riskFacts = riskFacts;
        this.legalIndexVersion = legalIndexVersion;
        this.knowledge = knowledge;
    }

    @Transactional(readOnly = true)
    public CustomerAssistantSnapshot create(String runId, AssistantConversationEntity conversation,
                                            String question, AssistantIntent intent) {
        CustomerEntity entity = customers.findById(conversation.getCustomerId())
                .filter(item -> !item.isDeleted())
                .orElseThrow(() -> new CustomerNotFoundException(conversation.getCustomerId()));
        CustomerProfile profile = new CustomerProfile(entity.getCustomerNo(), entity.getName(), entity.getIdCard(),
                entity.getType(), entity.getIndustry(), entity.getRegion(), entity.getRegCapital());
        List<TransactionRecord> transactions = List.copyOf(dataSource.transactionsOf(profile.id()));
        List<ShareholdingRecord> shareholdings = List.copyOf(dataSource.shareholdingsOf(profile.id()));
        var sanctions = List.copyOf(riskFacts.searchSanctions(profile));
        var facts = riskFacts.assembleFrom(transactions, shareholdings, sanctions, RiskLevel.LOW.label());

        AssistantCustomerView customerView = new AssistantCustomerView("CURRENT_CUSTOMER", profile.type(),
                profile.industry(), profile.region(), profile.regCapital(), entity.getStatus());
        TransactionRiskView transactionView = transactionView(transactions, facts);
        OwnershipRiskView ownershipView = ownershipView(shareholdings, facts.uboRiskSeverity());
        SanctionRiskView sanctionView = new SanctionRiskView(facts.sanctionHit(), sanctions.size(),
                facts.maxSeverity(), sanctions.stream().map(item -> item.listType())
                .filter(item -> item != null && !item.isBlank()).distinct().sorted().toList());

        List<AssistantEvidence> evidence = new ArrayList<>(evidence(customerView, transactionView, ownershipView,
                sanctionView, dataSource.sourceSystem(), dataSource.sourceVersion()));
        var knowledgeBundle = knowledge.retrieve(question, intent, dataSource.asOfTime());
        evidence.addAll(knowledgeBundle.evidence());
        evidence = List.copyOf(evidence);
        String digest = digest(customerView, transactionView, ownershipView, sanctionView, evidence);
        String indexVersion = safe(knowledgeBundle.version(), safe(legalIndexVersion.activeVersion(), "unavailable"));
        return new CustomerAssistantSnapshot("assistant-" + runId, conversation.getId(), runId,
                dataSource.asOfTime(), customerView, transactionView, ownershipView, sanctionView,
                evidence, safe(dataSource.sourceSystem(), "UNKNOWN"), safe(dataSource.sourceVersion(), "unknown"),
                indexVersion, digest);
    }

    private TransactionRiskView transactionView(List<TransactionRecord> transactions,
                                                com.bank.aml.risk.RiskContext facts) {
        BigDecimal total = transactions.stream().map(TransactionRecord::amount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = transactions.isEmpty() ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_UP);
        List<String> currencies = transactions.stream().map(TransactionRecord::currency)
                .filter(item -> item != null && !item.isBlank()).distinct().sorted().toList();
        List<String> regions = transactions.stream().map(TransactionRecord::country)
                .filter(java.util.Objects::nonNull).map(country -> country.label()).distinct().sorted().toList();
        return new TransactionRiskView(transactions.size(), total.setScale(2, RoundingMode.HALF_UP), average,
                round(facts.nightRatio()), round(facts.crossRatio()), facts.largeCount(),
                facts.transactionPatternSeverity(), currencies, regions, facts.transactionDataComplete());
    }

    private OwnershipRiskView ownershipView(List<ShareholdingRecord> records, int severity) {
        var relations = records.stream().map(item -> new OwnershipRiskView.OwnershipRelationView(
                maskName(item.holder()), item.holderType(), item.ratio(), item.level())).toList();
        return new OwnershipRiskView(records.size(), severity, relations);
    }

    private List<AssistantEvidence> evidence(AssistantCustomerView customer, TransactionRiskView tx,
                                             OwnershipRiskView ownership, SanctionRiskView sanction,
                                             String sourceSystem, String sourceVersion) {
        String source = safe(sourceSystem, "UNKNOWN") + "/" + safe(sourceVersion, "unknown");
        List<EvidenceDraft> drafts = new ArrayList<>();
        drafts.add(new EvidenceDraft(AssistantEvidence.EvidenceType.CUSTOMER_PROFILE, "客户画像",
                "客户类型=" + safe(customer.customerType(), "未知") + "，行业=" + safe(customer.industry(), "未知")
                        + "，地区=" + safe(customer.region(), "未知") + "，状态=" + customer.status()));
        drafts.add(new EvidenceDraft(AssistantEvidence.EvidenceType.TRANSACTION_AGGREGATE, "交易聚合",
                "交易笔数=" + tx.transactionCount() + "，总额=" + tx.totalAmount() + "，夜间占比="
                        + tx.nightRatio() + "% ，跨境占比=" + tx.crossBorderRatio() + "% ，大额笔数="
                        + tx.largeTransactionCount()));
        drafts.add(new EvidenceDraft(AssistantEvidence.EvidenceType.OWNERSHIP, "股权风险摘要",
                "股权关系数=" + ownership.relationCount() + "，UBO风险严重度=" + ownership.uboRiskSeverity()));
        drafts.add(new EvidenceDraft(AssistantEvidence.EvidenceType.SANCTION, "制裁筛查摘要",
                "是否命中=" + sanction.hit() + "，命中数=" + sanction.hitCount() + "，最高严重度="
                        + sanction.maxSeverity()));
        return drafts.stream().map(item -> {
            String hash = AssistantDigests.sha256(source + "|" + item.type + "|" + item.summary);
            return new AssistantEvidence(item.type.name() + ":" + hash, item.type, item.title, item.summary, source);
        }).toList();
    }

    private String digest(AssistantCustomerView customer, TransactionRiskView tx,
                          OwnershipRiskView ownership, SanctionRiskView sanction,
                          List<AssistantEvidence> evidence) {
        String canonical = customer + "|" + tx + "|" + ownership + "|" + sanction + "|" + evidence;
        return AssistantDigests.sha256(canonical);
    }

    private static String maskName(String value) {
        if (value == null || value.isBlank()) return "未知主体";
        String normalized = value.trim();
        return normalized.substring(0, 1) + "***";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record EvidenceDraft(AssistantEvidence.EvidenceType type, String title, String summary) {}
}
