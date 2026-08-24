package com.bank.aml.messaging;

import com.bank.aml.messaging.OutboxEvent.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 待发布事件：普通 PENDING（且重试时间已到）+ 发布中但已超时的陈旧 Claim（崩溃残留，可重新抢占）。
     */
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE (e.status = :pending AND e.nextRetryAt <= :now)
               OR (e.status = :publishing AND e.publishedAt <= :staleBefore)
            ORDER BY e.id ASC
            """)
    List<OutboxEvent> findPublishable(@Param("pending") OutboxStatus pending,
                                      @Param("publishing") OutboxStatus publishing,
                                      @Param("now") LocalDateTime now,
                                      @Param("staleBefore") LocalDateTime staleBefore,
                                      Pageable pageable);

    /**
     * 原子抢占：仅 PENDING 可抢占；陈旧 PUBLISHING（崩溃残留）可重新抢占。
     * 影响行数 1 = 抢占成功；0 = 已被其他发布器抢占，直接跳过。
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :publishing, e.publishedAt = :now,
                e.claimOwner = :claimOwner, e.claimVersion = e.claimVersion + 1
            WHERE e.id = :id AND (
                e.status = :pending
                OR (e.status = :publishing AND e.publishedAt <= :staleBefore))
            """)
    int claimPublishing(@Param("id") Long id,
                        @Param("publishing") OutboxStatus publishing,
                        @Param("pending") OutboxStatus pending,
                        @Param("claimOwner") String claimOwner,
                        @Param("now") LocalDateTime now,
                        @Param("staleBefore") LocalDateTime staleBefore);

    /** XADD 成功后确认发布 */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :published, e.publishedAt = :now
            WHERE e.id = :id AND e.status = :publishing
              AND e.claimOwner = :claimOwner AND e.claimVersion = :claimVersion
            """)
    int markPublished(@Param("id") Long id,
                      @Param("published") OutboxStatus published,
                      @Param("publishing") OutboxStatus publishing,
                      @Param("claimOwner") String claimOwner,
                      @Param("claimVersion") long claimVersion,
                      @Param("now") LocalDateTime now);

    /** XADD 失败：释放抢占并回退 PENDING + 指数退避（下次轮询重试） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :pending, e.retryCount = :retryCount, e.nextRetryAt = :nextRetryAt
            WHERE e.id = :id AND e.status = :publishing
              AND e.claimOwner = :claimOwner AND e.claimVersion = :claimVersion
            """)
    int releaseClaim(@Param("id") Long id,
                     @Param("pending") OutboxStatus pending,
                     @Param("publishing") OutboxStatus publishing,
                     @Param("claimOwner") String claimOwner,
                     @Param("claimVersion") long claimVersion,
                     @Param("retryCount") int retryCount,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** XADD 重试超限：PUBLISHING → DEAD（仅限仍持有效 Claim 的事件） */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :dead, e.retryCount = :retryCount
            WHERE e.id = :id AND e.status = :publishing
              AND e.claimOwner = :claimOwner AND e.claimVersion = :claimVersion
            """)
    int failDead(@Param("id") Long id,
                 @Param("dead") OutboxStatus dead,
                 @Param("publishing") OutboxStatus publishing,
                 @Param("claimOwner") String claimOwner,
                 @Param("claimVersion") long claimVersion,
                 @Param("retryCount") int retryCount);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
