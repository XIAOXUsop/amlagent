package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import com.bank.aml.security.IdCardCipher;

/**
 * 客户/人员主数据（新建预警工单可选客户的数据源）。
 * <p>删除采用逻辑删除（deleted=true），保护历史工单关联；证件号必填且唯一。
 */
@Entity
@Table(name = "customer")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String customerNo;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 512)
    private String idCard;

    @Column(length = 64, columnDefinition = "char(64)")
    private String idCardFingerprint;

    @Column(length = 32)
    private String type;

    @Column(length = 64)
    private String industry;

    @Column(length = 64)
    private String region;

    @Column(length = 128)
    private String regCapital;

    /** ENABLED / DISABLED */
    @Column(nullable = false, length = 16)
    private String status = "ENABLED";

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private boolean deleted = false;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerNo() {
        return customerNo;
    }

    public void setCustomerNo(String customerNo) {
        this.customerNo = customerNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIdCard() {
        return IdCardCipher.decrypt(idCard);
    }

    public void setIdCard(String idCard) {
        this.idCard = IdCardCipher.encrypt(idCard);
        this.idCardFingerprint = IdCardCipher.fingerprint(idCard);
    }

    public String getIdCardFingerprint() { return idCardFingerprint; }
    public void refreshIdCardFingerprint() { this.idCardFingerprint = IdCardCipher.fingerprint(getIdCard()); }
    public boolean isIdCardEncrypted() { return IdCardCipher.isEncrypted(idCard); }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRegCapital() {
        return regCapital;
    }

    public void setRegCapital(String regCapital) {
        this.regCapital = regCapital;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
