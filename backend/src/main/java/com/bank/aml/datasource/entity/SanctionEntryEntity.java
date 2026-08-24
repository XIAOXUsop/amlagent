package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 经合规数据接入流程落库的制裁/关注名单条目。 */
@Entity
@Table(name = "sanction_entry")
public class SanctionEntryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 128) private String subjectName;
    @Column(length = 128) private String identityNumber;
    @Column(nullable = false, length = 64) private String listName;
    @Column(length = 512) private String reason;
    @Column(nullable = false) private int severity;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private LocalDateTime sourceUpdatedAt;

    public Long getId() { return id; }
    public String getSubjectName() { return subjectName; }
    public String getIdentityNumber() { return identityNumber; }
    public String getListName() { return listName; }
    public String getReason() { return reason; }
    public int getSeverity() { return severity; }
    public boolean isEnabled() { return enabled; }
    public LocalDateTime getSourceUpdatedAt() { return sourceUpdatedAt; }
}
