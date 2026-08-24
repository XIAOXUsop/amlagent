package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CustomerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {

    List<CustomerEntity> findByDeletedFalseOrderByCustomerNoAsc();

    Optional<CustomerEntity> findByCustomerNoAndDeletedFalse(String customerNo);

    Optional<CustomerEntity> findByIdCardFingerprintAndDeletedFalse(String idCardFingerprint);

    boolean existsByCustomerNoAndDeletedFalse(String customerNo);

    boolean existsByIdCardFingerprintAndDeletedFalse(String idCardFingerprint);

    /** 证件号数据库唯一键包含逻辑删除记录，创建前需按全量检查，避免只查 active 后撞库。 */
    boolean existsByIdCardFingerprint(String idCardFingerprint);

    Page<CustomerEntity> findByDeletedFalse(Pageable pageable);

    @Query("""
            SELECT c FROM CustomerEntity c
            WHERE c.deleted = false
              AND (c.customerNo LIKE %:kw% OR c.name LIKE %:kw%)
            """)
    Page<CustomerEntity> search(@Param("kw") String keyword, Pageable pageable);
}
