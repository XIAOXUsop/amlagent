package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.RagIndexManifestEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagIndexManifestRepository extends JpaRepository<RagIndexManifestEntity, String> {

    List<RagIndexManifestEntity> findAllByOrderByCreatedAtDesc();

    List<RagIndexManifestEntity> findByStatusOrderByUpdatedAtDesc(String status);

}
