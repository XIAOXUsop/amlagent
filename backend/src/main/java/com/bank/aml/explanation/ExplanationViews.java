package com.bank.aml.explanation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 解释核验工作区对外视图（v2 计划 §13/§14）。 */
public final class ExplanationViews {

    private ExplanationViews() {
    }

    /** 单元条目：工作区首行显示，支持同案“1 条有解释、1 条有疑点、1 条未决”的混合呈现。 */
    public record UnitView(Long unitId, Long alertId, String externalAlertId, Long hypothesisId, String policyCode,
            int draftRevision, String draftJson, boolean hasCurrentSubmission, Long currentSubmissionId,
            ExplanationOutcome currentOutcome, boolean criticalUnknown, boolean followupRequired,
            List<String> blockers) {
    }

    /** 工作区：全量范围不受页面筛选影响；同时返回案件事实序号。 */
    public record WorkspaceView(Long caseId, int contractVersion, long caseFactsEpoch, boolean stale,
            List<UnitView> units, List<IssueView> issues, List<EvidenceView> artifacts, List<ClaimView> claims,
            List<String> generalBlockers, boolean canExclude, boolean canConfirm, List<String> confirmBlockers,
            List<String> excludeBlockers) {
    }

    /** 问题条目（差异/反证/缺口）。 */
    public record IssueView(Long issueId, Long unitId, String issueKey, IssueSeverity severity, String questionCode,
            String transactionIds, String description, IssueDisposition disposition, String dispositionReason,
            String resolvedBy, String confirmedBy, int revision) {
    }

    /** 材料版本条目：登记 ≠ 已核验；availability/integrity 是两个独立维度。 */
    public record EvidenceView(Long artifactVersionId, String artifactKey, int version, String sourceSystem,
            String sourceReference, String contentSha256, String claimedSha256, String availability,
            String integrityStatus, String capturedBy, Instant capturedAt) {
    }

    /** 核验动作条目。 */
    public record VerificationView(Long eventId, Long artifactVersionId, String method, String observedFacts,
            String limitations, String result, String actor, Instant eventTime) {
    }

    /** 提交结果：返回 submissionId 与当前状态（幂等重放时可能是 STALE，V2-22）。 */
    public record SubmissionResult(Long submissionId, Long unitId, int submissionNo, ExplanationOutcome outcome,
            SubmissionState state, ExplanationOutcome hypothesisAggregate, String reviewBasisToken,
            List<String> messages) {
    }

    /** 最终复核候选依据（§13 GET /review-basis）。 */
    public record ReviewBasisView(Long caseId, long caseFactsEpoch, String reviewBasisToken,
            boolean reviewerIndependent, boolean canExclude, boolean canConfirm, List<String> confirmBlockers,
            List<String> excludeBlockers, List<UnitView> adoptedUnits, List<IssueView> openIssues,
            Map<String, String> unitOutcomes) {
    }

    /** 问题降级提案视图（A5-04 双人确认流程）。 */
    public record IssueReviewView(Long proposalId, Long caseId, Long issueId, int proposalRevision,
            IssueSeverity originalSeverity, IssueSeverity proposedSeverity, String reason, String evidenceReference,
            String proposedBy, Instant proposedAt, String status, String confirmedBy, Instant confirmedAt,
            String rejectedReason) {
    }

    /** Claim 视图（G1-2）：状态 + 关联（含来源家族）+ 独立来源计数（RF-20）。 */
    public record ClaimView(Long claimId, String claimCode, String status, String importance, String judgement,
            String methodNote, String limitations, String notApplicableReason, int claimRevision, String updatedBy,
            List<ClaimLinkView> links, int independentSourceCount) {
    }

    public record ClaimLinkView(Long linkId, Long artifactVersionId, String direction, String sourceFamily,
            String location, String note) {
    }

    /**
     * 定向核验建议（v3 计划 §7）：可解释的"下一步查什么"。 排序：来源/身份完整性 → 会改变决定的矛盾 → 决定关键未知 → 即将到期义务 → 补充背景。
     */
    public record NextActionView(int priority, String category, String relatedClaimCode, String relatedIssueKey,
            String missingFact, String suggestedSource, String suggestedAction, String whatItCanChange,
            String alternative) {
    }

}
