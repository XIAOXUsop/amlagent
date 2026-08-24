package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 生产股权/控制关系事实的只读 JPA 映射。 */
@Entity
@Table(name = "customer_shareholding")
public class CustomerShareholdingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32) private String customerNo;
    @Column(nullable = false, length = 128) private String holderName;
    @Column(nullable = false, length = 64) private String holderType;
    @Column(nullable = false, precision = 8, scale = 6) private BigDecimal ownershipRatio;
    @Column(nullable = false, length = 16) private String ownershipLevel;
    @Column(nullable = false) private LocalDateTime sourceUpdatedAt;

    public Long getId() { return id; }
    public String getCustomerNo() { return customerNo; }
    public String getHolderName() { return holderName; }
    public String getHolderType() { return holderType; }
    public BigDecimal getOwnershipRatio() { return ownershipRatio; }
    public String getOwnershipLevel() { return ownershipLevel; }
    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
}
