package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CustomerTransactionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerTransactionRepository extends JpaRepository<CustomerTransactionEntity, Long> {

    List<CustomerTransactionEntity> findByCustomerNoOrderByTransactedAtAsc(String customerNo);

}
