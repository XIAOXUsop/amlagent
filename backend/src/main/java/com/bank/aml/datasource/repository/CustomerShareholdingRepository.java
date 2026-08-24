package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CustomerShareholdingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerShareholdingRepository extends JpaRepository<CustomerShareholdingEntity, Long> {
    List<CustomerShareholdingEntity> findByCustomerNoOrderByIdAsc(String customerNo);
}
