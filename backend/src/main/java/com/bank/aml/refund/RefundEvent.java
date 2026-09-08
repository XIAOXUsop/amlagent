package com.bank.aml.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款事件（G2-1/V35）：来源幂等键唯一；事实状态与调查处置分离；冲正指向原入账事件。 */
@Entity
@Table(name = "refund_event")
public class RefundEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 64)
    private String sourceSystem;

    @Column(name = "external_event_id", nullable = false, length = 96)
    private String externalEventId;

    /** REQUESTED / POSTED / REVERSED。 */
    @Column(name = "event_status", nullable = false, length = 24)
    private String eventStatus;

    @Column(name = "payer_subject", nullable = false, length = 128)
    private String payerSubject;

    @Column(name = "payee_subject", nullable = false, length = 128)
    private String payeeSubject;

    @Column(name = "payee_account_ref", length = 96)
    private String payeeAccountRef;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "payload_digest", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String payloadDigest;

    @Column(name = "reversed_event_id")
    private Long reversedEventId;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String value) { sourceSystem = value; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String value) { externalEventId = value; }
    public String getEventStatus() { return eventStatus; }
    public void setEventStatus(String value) { eventStatus = value; }
    public String getPayerSubject() { return payerSubject; }
    public void setPayerSubject(String value) { payerSubject = value; }
    public String getPayeeSubject() { return payeeSubject; }
    public void setPayeeSubject(String value) { payeeSubject = value; }
    public String getPayeeAccountRef() { return payeeAccountRef; }
    public void setPayeeAccountRef(String value) { payeeAccountRef = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
    public LocalDateTime getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(LocalDateTime value) { effectiveAt = value; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime value) { recordedAt = value; }
    public String getPayloadDigest() { return payloadDigest; }
    public void setPayloadDigest(String value) { payloadDigest = value; }
    public Long getReversedEventId() { return reversedEventId; }
    public void setReversedEventId(Long value) { reversedEventId = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
