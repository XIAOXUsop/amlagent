package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CustomerShareholdingEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerShareholdingRepository extends JpaRepository<CustomerShareholdingEntity, Long> {

    List<CustomerShareholdingEntity> findByCustomerNoOrderByIdAsc(String customerNo);

}
