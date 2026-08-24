package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.RagIndexManifestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagIndexManifestRepository extends JpaRepository<RagIndexManifestEntity, String> {
    List<RagIndexManifestEntity> findAllByOrderByCreatedAtDesc();
    List<RagIndexManifestEntity> findByStatusOrderByUpdatedAtDesc(String status);
}
