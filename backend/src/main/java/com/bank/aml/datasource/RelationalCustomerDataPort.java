package com.bank.aml.datasource;

import com.bank.aml.common.enums.CountryRegion;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import com.bank.aml.datasource.repository.CustomerShareholdingRepository;
import com.bank.aml.datasource.repository.CustomerTransactionRepository;
import com.bank.aml.datasource.repository.SanctionEntryRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 生产数据 Adapter：全部风险事实来自持久化的上游同步表，绝不为缺失数据生成“正常”交易或股权。
 * 空集合表示数据缺失/未同步，由快照数据完整性与 Guardrails 决定是否转人工。
 */
@Component
@Primary
@Profile("prod")
public class RelationalCustomerDataPort implements CustomerDataPort, CustomerDataRefresh {
    private final CustomerRepository customers;
    private final CustomerTransactionRepository transactions;
    private final CustomerShareholdingRepository shareholdings;
    private final SanctionEntryRepository sanctions;
    private final String sourceVersion;

    public RelationalCustomerDataPort(CustomerRepository customers,
                                      CustomerTransactionRepository transactions,
                                      CustomerShareholdingRepository shareholdings,
                                      SanctionEntryRepository sanctions,
                                      @Value("${aml.data.source-version:bank-sync-v1}") String sourceVersion) {
        this.customers = customers;
        this.transactions = transactions;
        this.shareholdings = shareholdings;
        this.sanctions = sanctions;
        this.sourceVersion = sourceVersion;
    }

    @Override
    public Optional<CustomerProfile> findCustomer(String customerId) {
        return customers.findByCustomerNoAndDeletedFalse(customerId)
                .filter(entity -> "ENABLED".equalsIgnoreCase(entity.getStatus()))
                .map(this::profile);
    }

    @Override
    public List<CustomerProfile> allCustomers() {
        return customers.findByDeletedFalseOrderByCustomerNoAsc().stream()
                .filter(entity -> "ENABLED".equalsIgnoreCase(entity.getStatus()))
                .map(this::profile)
                .toList();
    }

    @Override
    public List<TransactionRecord> transactionsOf(String customerId) {
        return transactions.findByCustomerNoOrderByTransactedAtAsc(customerId).stream()
                .map(entity -> new TransactionRecord(entity.getTransactedAt(), entity.getAmount(),
                        entity.getDirection(), entity.getCounterparty(), region(entity.getCounterpartyRegion()),
                        entity.getChannel(), entity.getPurpose(), entity.getCurrency()))
                .toList();
    }

    @Override
    public List<ShareholdingRecord> shareholdingsOf(String customerId) {
        return shareholdings.findByCustomerNoOrderByIdAsc(customerId).stream()
                .map(entity -> new ShareholdingRecord(entity.getHolderName(), entity.getHolderType(),
                        entity.getOwnershipRatio(), entity.getOwnershipLevel()))
                .toList();
    }

    @Override
    public List<SanctionRecord> searchSanctions(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        return sanctions.searchEnabled(keyword.trim(), PageRequest.of(0, 50)).stream()
                .map(entity -> new SanctionRecord(entity.getSubjectName(), entity.getIdentityNumber(),
                        entity.getListName(), entity.getReason(), entity.getSeverity()))
                .toList();
    }

    @Override public String sourceSystem() { return "BANK_RELATIONAL_SYNC"; }
    @Override public String sourceVersion() { return sourceVersion; }
    @Override public Instant asOfTime() { return Instant.now(); }
    @Override public void refresh() { /* 生产实现逐次查询数据库，不维护进程内事实缓存。 */ }

    private CustomerProfile profile(CustomerEntity entity) {
        return new CustomerProfile(entity.getCustomerNo(), entity.getName(), entity.getIdCard(), entity.getType(),
                entity.getIndustry(), entity.getRegion(), entity.getRegCapital());
    }

    private CountryRegion region(String value) {
        if (value == null || value.isBlank()) return CountryRegion.OTHER;
        try {
            return CountryRegion.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CountryRegion.OTHER;
        }
    }
}
