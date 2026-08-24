package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 多实例共享的法规索引发布指针与构建租约。 */
@Entity
@Table(name = "legal_index_state")
public class LegalIndexStateEntity {
    @Id @Column(length = 32) private String id;
    @Column(length = 64) private String activeVersion;
    @Column(length = 64) private String previousVersion;
    @Column(length = 64) private String buildingVersion;
    @Column(length = 64) private String buildOwner;
    private LocalDateTime buildLeaseUntil;
    @Column(nullable = false) private int segmentCount;
    @Column(nullable = false) private LocalDateTime updatedAt;

    public String getId() { return id; }
    public String getActiveVersion() { return activeVersion; }
    public void setActiveVersion(String activeVersion) { this.activeVersion = activeVersion; }
    public String getPreviousVersion() { return previousVersion; }
    public void setPreviousVersion(String previousVersion) { this.previousVersion = previousVersion; }
    public String getBuildingVersion() { return buildingVersion; }
    public String getBuildOwner() { return buildOwner; }
    public LocalDateTime getBuildLeaseUntil() { return buildLeaseUntil; }
    public int getSegmentCount() { return segmentCount; }
    public void setSegmentCount(int segmentCount) { this.segmentCount = segmentCount; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
