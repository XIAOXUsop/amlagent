package com.bank.aml.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EnhancedDueDiligenceRequestRepository
        extends JpaRepository<EnhancedDueDiligenceRequest, Long> {

    List<EnhancedDueDiligenceRequest> findByCaseIdOrderByRoundNoAsc(Long caseId);

    Optional<EnhancedDueDiligenceRequest> findTopByCaseIdOrderByRoundNoDesc(Long caseId);

    Optional<EnhancedDueDiligenceRequest> findByIdAndCaseId(Long id, Long caseId);

    List<EnhancedDueDiligenceRequest> findByAssignedToAndStatusOrderByDueAtAsc(
            String assignedTo, EnhancedDueDiligenceStatus status);

    List<EnhancedDueDiligenceRequest> findByStatusOrderByDueAtAsc(EnhancedDueDiligenceStatus status);

    List<EnhancedDueDiligenceRequest> findByCaseIdAndStatusOrderByIdAsc(Long caseId,
                                                                        EnhancedDueDiligenceStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE EnhancedDueDiligenceRequest r
            SET r.status = :submitted, r.responseSummary = :summary,
                r.evidenceReferencesJson = :referencesJson, r.respondedBy = :respondedBy,
                r.respondedAt = :respondedAt, r.revision = r.revision + 1
            WHERE r.id = :id AND r.caseId = :caseId AND r.status = :open AND r.revision = :expectedRevision
            """)
    int submitResponse(@Param("id") Long id,
                       @Param("caseId") Long caseId,
                       @Param("open") EnhancedDueDiligenceStatus open,
                       @Param("submitted") EnhancedDueDiligenceStatus submitted,
                       @Param("expectedRevision") int expectedRevision,
                       @Param("summary") String summary,
                       @Param("referencesJson") String referencesJson,
                       @Param("respondedBy") String respondedBy,
                       @Param("respondedAt") LocalDateTime respondedAt);

    /** A5-08：明确完成任务的条件更新——状态与版本绑定；两位复核人竞争完成仅一方成功。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE EnhancedDueDiligenceRequest r
            SET r.status = :resolved, r.resolutionReason = :reason,
                r.resolvedBy = :resolvedBy, r.resolvedAt = :resolvedAt, r.revision = r.revision + 1
            WHERE r.id = :id AND r.caseId = :caseId AND r.status = :submittedStatus
              AND r.revision = :expectedRevision
            """)
    int completeTask(@Param("id") Long id,
                     @Param("caseId") Long caseId,
                     @Param("submittedStatus") EnhancedDueDiligenceStatus submittedStatus,
                     @Param("resolved") EnhancedDueDiligenceStatus resolved,
                     @Param("expectedRevision") int expectedRevision,
                     @Param("reason") String reason,
                     @Param("resolvedBy") String resolvedBy,
                     @Param("resolvedAt") LocalDateTime resolvedAt);
}
