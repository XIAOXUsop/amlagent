package com.bank.aml.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款分配（G2-1/V35）：退款与原付款显式多对多；分配不变式由 RefundLedgerService 强制。 */
@Entity
@Table(name = "refund_allocation")
public class RefundAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(name = "refund_event_id", nullable = false)
    private Long refundEventId;

    @Column(name = "original_transaction_id", nullable = false, length = 64)
    private String originalTransactionId;

    /** 原付款分配键（订单/义务行）。 */
    @Column(name = "original_allocation_key", nullable = false, length = 96)
    private String originalAllocationKey;

    @Column(name = "allocated_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "returned_obligation_ref", length = 96)
    private String returnedObligationRef;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long value) {
        caseId = value;
    }

    public Long getRefundEventId() {
        return refundEventId;
    }

    public void setRefundEventId(Long value) {
        refundEventId = value;
    }

    public String getOriginalTransactionId() {
        return originalTransactionId;
    }

    public void setOriginalTransactionId(String value) {
        originalTransactionId = value;
    }

    public String getOriginalAllocationKey() {
        return originalAllocationKey;
    }

    public void setOriginalAllocationKey(String value) {
        originalAllocationKey = value;
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }

    public void setAllocatedAmount(BigDecimal value) {
        allocatedAmount = value;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String value) {
        currency = value;
    }

    public String getReturnedObligationRef() {
        return returnedObligationRef;
    }

    public void setReturnedObligationRef(String value) {
        returnedObligationRef = value;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        createdBy = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        createdAt = value;
    }

}
