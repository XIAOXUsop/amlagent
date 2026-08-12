package com.bank.aml.datasource.repository;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface CaseRepository extends JpaRepository<CaseEntity, Long> {

    List<CaseEntity> findAllByOrderByCreatedAtDesc();

    Page<CaseEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<CaseEntity> findByStatusOrderByCreatedAtAsc(CaseStatus status);

    /** 执行超时的工单（Worker 崩溃等），用于接管恢复 */
    List<CaseEntity> findByStatusAndLockedAtBefore(CaseStatus status, java.time.LocalDateTime before);

    /**
     * 抢占工单执行权（条件更新，幂等）：仅 PENDING/FAILED 可抢占，executionVersion 自增。
     * 影响行数 = 1 表示抢占成功；= 0 表示已被其他 Worker 执行（重复消息直接忽略）。
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :running, c.executionVersion = c.executionVersion + 1,
                c.lockedBy = :worker, c.lockedAt = :now
            WHERE c.id = :id AND c.status IN :eligible
            """)
    int tryLock(@Param("id") Long id,
                @Param("worker") String worker,
                @Param("now") LocalDateTime now,
                @Param("running") CaseStatus running,
                @Param("eligible") List<CaseStatus> eligible);

    /** 释放执行锁并写入失败信息 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :status, c.lockedBy = NULL, c.lockedAt = NULL,
                c.retryCount = :retryCount, c.failureCode = :failureCode, c.failureMessage = :failureMessage
            WHERE c.id = :id
            """)
    int failCase(@Param("id") Long id,
                 @Param("status") CaseStatus status,
                 @Param("retryCount") int retryCount,
                 @Param("failureCode") String failureCode,
                 @Param("failureMessage") String failureMessage);
}
