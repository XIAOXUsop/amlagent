package com.bank.aml.datasource;

import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.domain.ShareholdingRecord;
import com.bank.aml.domain.TransactionRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 客户数据源 Port 接口：隔离 Mock 演示数据与真实数据源，只暴露领域模型。
 * <p>Mock 实现用于演示与测试；接入真实银行系统时，实现本接口（HTTP / 数据库 / CSV 批量导入）即可替换，
 * Agent 工具与 Guardrails 无需改动。
 */
public interface CustomerDataPort {

    Optional<CustomerProfile> findCustomer(String customerId);

    List<CustomerProfile> allCustomers();

    List<TransactionRecord> transactionsOf(String customerId);

    List<ShareholdingRecord> shareholdingsOf(String customerId);

    List<SanctionRecord> searchSanctions(String keyword);

    /** 数据源系统标识（用于工具结果溯源） */
    default String sourceSystem() {
        return "UNKNOWN";
    }

    /** 数据源版本（用于工具结果溯源） */
    default String sourceVersion() {
        return "unknown";
    }

    /** 数据截止时间（用于快照溯源） */
    default Instant asOfTime() {
        return Instant.now();
    }
}
