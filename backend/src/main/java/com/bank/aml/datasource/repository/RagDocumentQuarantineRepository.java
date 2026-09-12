package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.RagDocumentQuarantineEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagDocumentQuarantineRepository extends JpaRepository<RagDocumentQuarantineEntity, Long> {

    boolean existsBySourceFileAndFileHash(String sourceFile, String fileHash);

    List<RagDocumentQuarantineEntity> findTop100ByOrderByDetectedAtDesc();

}
