package com.bank.aml.datasource;

import com.bank.aml.datasource.mock.MockDataSource.Customer;
import com.bank.aml.datasource.mock.MockDataSource.SanctionEntry;
import com.bank.aml.datasource.mock.MockDataSource.Shareholding;
import com.bank.aml.datasource.mock.MockDataSource.Transaction;

import java.util.List;
import java.util.Optional;

/**
 * 客户数据源 Port 接口：隔离 Mock 演示数据与真实数据源。
 * <p>Mock 实现（{@code MockDataSource}）用于演示与测试；接入真实银行系统时，
 * 实现本接口（HTTP / 数据库 / CSV 批量导入）即可替换，Agent 工具与 Guardrails 无需改动。
 */
public interface CustomerDataPort {

    Optional<Customer> findCustomer(String customerId);

    List<Customer> allCustomers();

    List<Transaction> transactionsOf(String customerId);

    List<Shareholding> shareholdingsOf(String customerId);

    List<SanctionEntry> searchSanctions(String keyword);
}
