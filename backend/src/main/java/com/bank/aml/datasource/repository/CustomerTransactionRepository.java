package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CustomerTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerTransactionRepository extends JpaRepository<CustomerTransactionEntity, Long> {
    List<CustomerTransactionEntity> findByCustomerNoOrderByTransactedAtAsc(String customerNo);
}
