package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.RagDocumentQuarantineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagDocumentQuarantineRepository extends JpaRepository<RagDocumentQuarantineEntity, Long> {
    boolean existsBySourceFileAndFileHash(String sourceFile, String fileHash);
    java.util.List<RagDocumentQuarantineEntity> findTop100ByOrderByDetectedAtDesc();
}
