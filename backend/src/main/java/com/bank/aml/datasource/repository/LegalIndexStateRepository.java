package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.LegalIndexStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface LegalIndexStateRepository extends JpaRepository<LegalIndexStateEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LegalIndexStateEntity s WHERE s.id = :id")
    Optional<LegalIndexStateEntity> findForUpdate(@Param("id") String id);

    @Modifying @Transactional
    @Query(value = "INSERT IGNORE INTO legal_index_state (id, segment_count, updated_at) VALUES ('legal', 0, :now)",
            nativeQuery = true)
    int ensureStateRow(@Param("now") LocalDateTime now);

    @Modifying @Transactional
    @Query("""
            UPDATE LegalIndexStateEntity s SET s.buildingVersion = :version,
                s.buildOwner = :owner, s.buildLeaseUntil = :leaseUntil, s.updatedAt = :now
            WHERE s.id = 'legal' AND (s.buildOwner IS NULL OR s.buildLeaseUntil <= :now)
            """)
    int claimBuild(@Param("version") String version, @Param("owner") String owner,
                   @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Modifying @Transactional
    @Query("""
            UPDATE LegalIndexStateEntity s SET s.buildLeaseUntil = :leaseUntil, s.updatedAt = :now
            WHERE s.id = 'legal' AND s.buildOwner = :owner AND s.buildingVersion = :version
            """)
    int renewBuildLease(@Param("version") String version, @Param("owner") String owner,
                        @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Modifying @Transactional
    @Query("""
            UPDATE LegalIndexStateEntity s SET s.previousVersion = s.activeVersion,
                s.activeVersion = :version, s.segmentCount = :segmentCount,
                s.buildingVersion = NULL, s.buildOwner = NULL, s.buildLeaseUntil = NULL, s.updatedAt = :now
            WHERE s.id = 'legal' AND s.buildOwner = :owner AND s.buildingVersion = :version
            """)
    int activate(@Param("version") String version, @Param("owner") String owner,
                 @Param("segmentCount") int segmentCount, @Param("now") LocalDateTime now);

    @Modifying @Transactional
    @Query("""
            UPDATE LegalIndexStateEntity s SET s.buildingVersion = NULL, s.buildOwner = NULL,
                s.buildLeaseUntil = NULL, s.updatedAt = :now
            WHERE s.id = 'legal' AND s.buildOwner = :owner AND s.buildingVersion = :version
            """)
    int releaseBuild(@Param("version") String version, @Param("owner") String owner,
                     @Param("now") LocalDateTime now);

}
