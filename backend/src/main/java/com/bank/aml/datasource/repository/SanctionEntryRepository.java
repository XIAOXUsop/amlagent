package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.SanctionEntryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SanctionEntryRepository extends JpaRepository<SanctionEntryEntity, Long> {
    @Query("""
            SELECT s FROM SanctionEntryEntity s
            WHERE s.enabled = true AND (
                LOWER(s.subjectName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                LOWER(COALESCE(s.identityNumber, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            ORDER BY s.severity ASC, s.id ASC
            """)
    List<SanctionEntryEntity> searchEnabled(@Param("keyword") String keyword, Pageable pageable);
}
