package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 生产交易事实的只读 JPA 映射；记录由上游银行数据同步任务写入。 */
@Entity
@Table(name = "customer_transaction")
public class CustomerTransactionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32) private String customerNo;
    @Column(nullable = false) private LocalDateTime transactedAt;
    @Column(nullable = false, precision = 20, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 16) private String direction;
    @Column(nullable = false, length = 128) private String counterparty;
    @Column(nullable = false, length = 16) private String counterpartyRegion;
    @Column(length = 32) private String channel;
    @Column(length = 128) private String purpose;
    @Column(nullable = false, length = 8) private String currency;
    @Column(nullable = false) private LocalDateTime sourceUpdatedAt;

    public Long getId() { return id; }
    public String getCustomerNo() { return customerNo; }
    public LocalDateTime getTransactedAt() { return transactedAt; }
    public BigDecimal getAmount() { return amount; }
    public String getDirection() { return direction; }
    public String getCounterparty() { return counterparty; }
    public String getCounterpartyRegion() { return counterpartyRegion; }
    public String getChannel() { return channel; }
    public String getPurpose() { return purpose; }
    public String getCurrency() { return currency; }
    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
}
