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

    /** 重试等待中、到期需重新入队的工单 */
    List<CaseEntity> findByStatusAndNextRetryAtLessThanEqual(CaseStatus status, LocalDateTime now);

    /**
     * 抢占工单执行权（条件更新，幂等）：仅 PENDING/FAILED 可抢占，executionVersion 自增。
     * 影响行数 = 1 表示抢占成功；= 0 表示已被其他 Worker 执行（重复消息直接忽略）。
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :running, c.executionVersion = c.executionVersion + 1,
                c.lockedBy = :worker, c.lockedAt = :now, c.heartbeatAt = :now
            WHERE c.id = :id AND c.status IN :eligible
            """)
    int tryLock(@Param("id") Long id,
                @Param("worker") String worker,
                @Param("now") LocalDateTime now,
                @Param("running") CaseStatus running,
                @Param("eligible") List<CaseStatus> eligible);

    /** 刷新心跳（长模型调用期间周期性调用，避免被错误接管）；绑定 worker+executionVersion，陈旧心跳不越权 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.heartbeatAt = :now
            WHERE c.id = :id AND c.lockedBy = :worker AND c.executionVersion = :version
            """)
    int updateHeartbeat(@Param("id") Long id,
                        @Param("worker") String worker,
                        @Param("version") int version,
                        @Param("now") LocalDateTime now);

    /** 释放执行锁并写入失败信息；绑定 worker+executionVersion，被接管后陈旧失败写入不生效 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :status, c.lockedBy = NULL, c.lockedAt = NULL,
                c.retryCount = :retryCount, c.failureCode = :failureCode, c.failureMessage = :failureMessage
            WHERE c.id = :id AND c.lockedBy = :worker AND c.executionVersion = :version
            """)
    int failCase(@Param("id") Long id,
                 @Param("status") CaseStatus status,
                 @Param("retryCount") int retryCount,
                 @Param("failureCode") String failureCode,
                 @Param("failureMessage") String failureMessage,
                 @Param("worker") String worker,
                 @Param("version") int version);

    /** 置为 RETRY_WAIT 并记录下次重试时间（指数退避调度）；绑定 worker+executionVersion */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :retryWait, c.lockedBy = NULL, c.lockedAt = NULL,
                c.retryCount = :retryCount, c.failureCode = :failureCode, c.failureMessage = :failureMessage,
                c.nextRetryAt = :nextRetryAt
            WHERE c.id = :id AND c.lockedBy = :worker AND c.executionVersion = :version
            """)
    int markRetryWait(@Param("id") Long id,
                      @Param("retryWait") CaseStatus retryWait,
                      @Param("retryCount") int retryCount,
                      @Param("failureCode") String failureCode,
                      @Param("failureMessage") String failureMessage,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("worker") String worker,
                      @Param("version") int version);

    /** 正常完成落库（DONE/HOLD）：原子写入终态字段并释放锁；绑定 worker+executionVersion，被接管后丢弃陈旧写入 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :status, c.riskLevel = :riskLevel, c.rawRiskLevel = :rawRiskLevel,
                c.reportJson = :reportJson, c.summary = :summary,
                c.failureCode = NULL, c.failureMessage = NULL,
                c.lockedBy = NULL, c.lockedAt = NULL
            WHERE c.id = :id AND c.lockedBy = :worker AND c.executionVersion = :version
            """)
    int finishCase(@Param("id") Long id,
                   @Param("worker") String worker,
                   @Param("version") int version,
                   @Param("status") CaseStatus status,
                   @Param("riskLevel") String riskLevel,
                   @Param("rawRiskLevel") String rawRiskLevel,
                   @Param("reportJson") String reportJson,
                   @Param("summary") String summary);

    /** 重试到期后重新置为 PENDING（由 RetryScheduler 调用） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :pending, c.nextRetryAt = NULL
            WHERE c.id = :id AND c.status = :retryWait
            """)
    int requeueRetryWait(@Param("id") Long id,
                         @Param("pending") CaseStatus pending,
                         @Param("retryWait") CaseStatus retryWait);

    /** 接管超时工单：RUNNING → PENDING，retryCount+1，清锁（条件更新，避免并发重复接管） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :pending, c.retryCount = c.retryCount + 1,
                c.lockedBy = NULL, c.lockedAt = NULL, c.nextRetryAt = NULL,
                c.failureMessage = 'Worker 超时接管，重新投递'
            WHERE c.id = :id AND c.status = :running
            """)
    int reclaimStuckCase(@Param("id") Long id,
                         @Param("pending") CaseStatus pending,
                         @Param("running") CaseStatus running);

    /** 接管耗尽：RUNNING → FAILED（终态转人工） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :failed, c.lockedBy = NULL, c.lockedAt = NULL,
                c.failureCode = 'CLAIM_EXHAUSTED', c.failureMessage = '多次接管仍失败，转人工排查'
            WHERE c.id = :id AND c.status = :running
            """)
    int failReclaimExhausted(@Param("id") Long id,
                             @Param("failed") CaseStatus failed,
                             @Param("running") CaseStatus running);
}
