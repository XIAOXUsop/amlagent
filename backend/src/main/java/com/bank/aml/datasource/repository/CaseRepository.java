package com.bank.aml.datasource.repository;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CaseRepository extends JpaRepository<CaseEntity, Long> {

    /**
     * 人工处置与补充尽调回传共享同一案件行锁，保证跨表状态迁移串行化。 不能只分别锁案件表或任务表，否则 DONE 与 SUBMITTED 仍可能交错提交。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CaseEntity c WHERE c.id = :id")
    Optional<CaseEntity> findByIdForUpdate(@Param("id") Long id);

    Page<CaseEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<CaseEntity> findByStatusOrderByCreatedAtAsc(CaseStatus status);

    List<CaseEntity> findByCustomerIdAndStatusOrderByCreatedAtDesc(String customerId, CaseStatus status);

    List<CaseEntity> findByStatusInOrderByCreatedAtAsc(Collection<CaseStatus> statuses);

    long countByStatus(CaseStatus status);

    /** 执行超时的工单（Worker 崩溃等），用于接管恢复 */
    List<CaseEntity> findByStatusAndLockedAtBefore(CaseStatus status, LocalDateTime before);

    /** 重试等待中、到期需重新入队的工单 */
    List<CaseEntity> findByStatusAndNextRetryAtLessThanEqual(CaseStatus status, LocalDateTime now);

    /**
     * 抢占工单执行权（条件更新，幂等）：仅 PENDING 可抢占，executionVersion 自增； 消息 expectedVersion
     * 必须与当前版本匹配，延迟旧消息更新 0 行被丢弃。 影响行数 = 1 表示抢占成功；= 0 表示已被其他 Worker 执行或版本不匹配。
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :running, c.executionVersion = c.executionVersion + 1,
                c.lockedBy = :worker, c.lockedAt = :now, c.heartbeatAt = :now
            WHERE c.id = :id AND c.status IN :eligible AND c.executionVersion = :expectedVersion
            """)
    int tryLock(@Param("id") Long id, @Param("worker") String worker, @Param("now") LocalDateTime now,
            @Param("running") CaseStatus running, @Param("eligible") List<CaseStatus> eligible,
            @Param("expectedVersion") int expectedVersion);

    /** 刷新心跳（长模型调用期间周期性调用，避免被错误接管）；绑定 worker+executionVersion，陈旧心跳不越权 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.heartbeatAt = :now
            WHERE c.id = :id AND c.lockedBy = :worker AND c.executionVersion = :version
            """)
    int updateHeartbeat(@Param("id") Long id, @Param("worker") String worker, @Param("version") int version,
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
    int failCase(@Param("id") Long id, @Param("status") CaseStatus status, @Param("retryCount") int retryCount,
            @Param("failureCode") String failureCode, @Param("failureMessage") String failureMessage,
            @Param("worker") String worker, @Param("version") int version);

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
    int markRetryWait(@Param("id") Long id, @Param("retryWait") CaseStatus retryWait,
            @Param("retryCount") int retryCount, @Param("failureCode") String failureCode,
            @Param("failureMessage") String failureMessage, @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("worker") String worker, @Param("version") int version);

    /** 正常完成落库（DONE/HOLD）：原子写入终态字段并释放锁；绑定 worker+executionVersion，被接管后丢弃陈旧写入 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :status, c.riskLevel = :riskLevel, c.rawRiskLevel = :rawRiskLevel,
                c.reportJson = :reportJson, c.rawReportJson = :rawReportJson, c.summary = :summary,
                c.reportSource = :reportSource, c.snapshotId = :snapshotId,
                c.modelProvider = :modelProvider, c.modelName = :modelName, c.modelFallback = :modelFallback,
                c.failureCode = NULL, c.failureMessage = NULL,
                c.lockedBy = NULL, c.lockedAt = NULL
            WHERE c.id = :id AND c.lockedBy = :worker AND c.executionVersion = :version
            """)
    int finishCase(@Param("id") Long id, @Param("worker") String worker, @Param("version") int version,
            @Param("status") CaseStatus status, @Param("riskLevel") String riskLevel,
            @Param("rawRiskLevel") String rawRiskLevel, @Param("reportJson") String reportJson,
            @Param("rawReportJson") String rawReportJson, @Param("summary") String summary,
            @Param("reportSource") String reportSource, @Param("snapshotId") String snapshotId,
            @Param("modelProvider") String modelProvider, @Param("modelName") String modelName,
            @Param("modelFallback") boolean modelFallback);

    /** 重试到期后重新置为 PENDING（由 RetryScheduler 调用） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :pending, c.nextRetryAt = NULL
            WHERE c.id = :id AND c.status = :retryWait
            """)
    int requeueRetryWait(@Param("id") Long id, @Param("pending") CaseStatus pending,
            @Param("retryWait") CaseStatus retryWait);

    /**
     * 接管超时工单：RUNNING → PENDING，retryCount+1，清锁； 绑定 worker+executionVersion+heartbeat
     * 阈值，心跳在扫描后刷新则更新行数为 0，不误接管
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :pending, c.retryCount = c.retryCount + 1,
                c.lockedBy = NULL, c.lockedAt = NULL, c.heartbeatAt = NULL, c.nextRetryAt = NULL,
                c.failureMessage = 'Worker 超时接管，重新投递'
            WHERE c.id = :id AND c.status = :running
              AND c.executionVersion = :version
              AND c.lockedBy = :worker
              AND c.heartbeatAt < :heartbeatThreshold
            """)
    int reclaimStuckCase(@Param("id") Long id, @Param("pending") CaseStatus pending,
            @Param("running") CaseStatus running, @Param("version") int version, @Param("worker") String worker,
            @Param("heartbeatThreshold") LocalDateTime heartbeatThreshold);

    /** 接管耗尽：RUNNING → FAILED（终态转人工）；同样绑定 worker+version+heartbeat 阈值 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :failed, c.lockedBy = NULL, c.lockedAt = NULL, c.heartbeatAt = NULL,
                c.failureCode = 'CLAIM_EXHAUSTED', c.failureMessage = '多次接管仍失败，转人工排查'
            WHERE c.id = :id AND c.status = :running
              AND c.executionVersion = :version
              AND c.lockedBy = :worker
              AND c.heartbeatAt < :heartbeatThreshold
            """)
    int failReclaimExhausted(@Param("id") Long id, @Param("failed") CaseStatus failed,
            @Param("running") CaseStatus running, @Param("version") int version, @Param("worker") String worker,
            @Param("heartbeatThreshold") LocalDateTime heartbeatThreshold);

    /** 人工复核终态：HOLD → DONE，同时落业务处置与原因码。 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :status, c.reviewRevision = c.reviewRevision + 1,
                c.reviewDisposition = :disposition, c.reviewReasonCode = :reasonCode,
                c.reviewedAt = :reviewedAt, c.failureCode = NULL, c.failureMessage = NULL
            WHERE c.id = :id AND c.status = :hold AND c.reviewRevision = :expectedRevision
            """)
    int completeReview(@Param("id") Long id, @Param("status") CaseStatus status, @Param("hold") CaseStatus hold,
            @Param("expectedRevision") int expectedRevision, @Param("disposition") String disposition,
            @Param("reasonCode") String reasonCode, @Param("reviewedAt") LocalDateTime reviewedAt);

    /** 请求强化尽调：保持 HOLD，记录原因并递增 revision，等待补充材料后再次处置。 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.reviewRevision = c.reviewRevision + 1,
                c.reviewDisposition = :disposition, c.reviewReasonCode = :reasonCode,
                c.reviewedAt = :reviewedAt
            WHERE c.id = :id AND c.status = :hold AND c.reviewRevision = :expectedRevision
            """)
    int requestEnhancedDueDiligence(@Param("id") Long id, @Param("hold") CaseStatus hold,
            @Param("expectedRevision") int expectedRevision, @Param("disposition") String disposition,
            @Param("reasonCode") String reasonCode, @Param("reviewedAt") LocalDateTime reviewedAt);

    /** 报送完成：REPORT_PENDING → DONE。 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c SET c.status = :done
            WHERE c.id = :id AND c.status = :reportPending
            """)
    int completeSuspiciousReport(@Param("id") Long id, @Param("reportPending") CaseStatus reportPending,
            @Param("done") CaseStatus done);

    /** 外部退回补正：已完成案件重新进入报告待办。 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c SET c.status = :reportPending
            WHERE c.id = :id AND c.status = :done AND c.reviewDisposition = 'CONFIRM_SUSPICIOUS'
            """)
    int reopenSuspiciousReport(@Param("id") Long id, @Param("done") CaseStatus done,
            @Param("reportPending") CaseStatus reportPending);

    /** 人工重试：FAILED → PENDING，清锁、失败信息并清零重试计数（条件更新，非 FAILED 返回 0） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :pending, c.retryCount = 0, c.nextRetryAt = NULL,
                c.lockedBy = NULL, c.lockedAt = NULL, c.heartbeatAt = NULL,
                c.failureCode = NULL, c.failureMessage = NULL
            WHERE c.id = :id AND c.status = :failed
            """)
    int retryFailed(@Param("id") Long id, @Param("pending") CaseStatus pending, @Param("failed") CaseStatus failed);

    /** 死信重放：仅限指定死信 failureCode 的 FAILED → PENDING，重置重试次数（条件更新） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE CaseEntity c
            SET c.status = :pending, c.retryCount = 0, c.nextRetryAt = NULL,
                c.lockedBy = NULL, c.lockedAt = NULL, c.heartbeatAt = NULL,
                c.failureCode = NULL, c.failureMessage = NULL
            WHERE c.id = :id AND c.status = :failed AND c.failureCode IN :allowedFailureCodes
            """)
    int replayDeadLetter(@Param("id") Long id, @Param("pending") CaseStatus pending, @Param("failed") CaseStatus failed,
            @Param("allowedFailureCodes") Collection<String> allowedFailureCodes);

    /** 案件事实序号递增：范围/材料核验/当前提交/关键问题处置/政策绑定/任务义务变更时调用（v2 依据令牌绑定）。 */
    @Modifying
    @Transactional
    @Query("UPDATE CaseEntity c SET c.caseFactsEpoch = c.caseFactsEpoch + 1 WHERE c.id = :id")
    int bumpFactsEpoch(@Param("id") Long id);

}
