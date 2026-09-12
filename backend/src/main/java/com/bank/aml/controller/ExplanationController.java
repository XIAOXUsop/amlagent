package com.bank.aml.controller;

import com.bank.aml.explanation.ExplanationClaimService;
import com.bank.aml.explanation.ExplanationPolicyCatalog;
import com.bank.aml.explanation.ExplanationViews;
import com.bank.aml.explanation.ExplanationWorkspaceService;
import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.EnhancedDueDiligenceView;
import com.bank.aml.security.PromptInjectionGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解释核验工作区接口（v2 计划 §13；前缀 /api/cases/{caseId}/investigation 下新增）。
 * 全部写操作走同一业务服务与案件锁；旧调查写入入口对 v2 案件已关闭（V2-23）。
 */
@RestController
@RequestMapping("/api/cases/{caseId}/investigation")
@PreAuthorize("hasAnyRole('ANALYST','REVIEWER','ADMIN')")
public class ExplanationController {

    private final ExplanationWorkspaceService workspaceService;

    private final ExplanationPolicyCatalog policyCatalog;

    private final EnhancedDueDiligenceService eddService;

    private final PromptInjectionGuard injectionGuard;

    private final ExplanationClaimService claimService;

    public ExplanationController(ExplanationWorkspaceService workspaceService, ExplanationPolicyCatalog policyCatalog,
            EnhancedDueDiligenceService eddService, PromptInjectionGuard injectionGuard,
            ExplanationClaimService claimService) {
        this.workspaceService = workspaceService;
        this.policyCatalog = policyCatalog;
        this.eddService = eddService;
        this.injectionGuard = injectionGuard;
        this.claimService = claimService;
    }

    private String operator() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** 工作区：全部预警单元、范围、六问题、待分派材料及阻断项；返回 caseFactsEpoch。 */
    @GetMapping("/explanation-workspace")
    public ExplanationViews.WorkspaceView workspace(@PathVariable Long caseId) {
        return workspaceService.openWorkspace(caseId);
    }

    /** 抓取受控材料（来源内容与摘要由服务端真实取得，A5-01）；登记 ≠ 已核验。 */
    @PostMapping("/evidence/captures")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.EvidenceView capture(@PathVariable Long caseId,
            @Valid @RequestBody CaptureRequest request) {
        return workspaceService.captureEvidence(caseId, request.sourceSystem(), request.sourceReference(), operator());
    }

    /** 记录具体核验动作（方法、观察、限制、结果）。 */
    @PostMapping("/evidence/{versionId}/verifications")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.VerificationView verify(@PathVariable Long caseId, @PathVariable Long versionId,
            @Valid @RequestBody VerificationRequest request) {
        if (injectionGuard.scan(request.observedFacts()).suspicious()) {
            throw new IllegalArgumentException("核验事实包含不被允许的内容");
        }
        return workspaceService.recordVerification(caseId, versionId, request.method(), request.observedFacts(),
                request.limitations(), request.result(), operator(), request.subjectFactKey());
    }

    /** 保存六问题草稿（expectedDraftRevision 乐观锁；不产生最终结论）。 */
    @PutMapping("/units/{unitId}/draft")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.UnitView saveDraft(@PathVariable Long caseId, @PathVariable Long unitId,
            @Valid @RequestBody DraftRequest request) {
        if (injectionGuard.scan(request.draftJson()).suspicious()) {
            throw new IllegalArgumentException("草稿包含不被允许的内容");
        }
        return workspaceService.saveDraft(caseId, unitId, request.expectedDraftRevision(), request.draftJson(),
                operator());
    }

    /** 原子提交本单元建议与当前覆盖（草稿版本 + 依据令牌 + 幂等键）。 */
    @PostMapping("/units/{unitId}/submissions")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.SubmissionResult submit(@PathVariable Long caseId, @PathVariable Long unitId,
            @Valid @RequestBody SubmitRequest request) {
        return workspaceService.submitUnit(caseId, unitId, request.expectedDraftRevision(), request.reviewBasisToken(),
                request.idempotencyKey(), operator());
    }

    /** 修订：撤回当前提交并开始下一草稿（旧提交可回放但不可被最终采用）。 */
    @PostMapping("/units/{unitId}/amendments")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.UnitView amend(@PathVariable Long caseId, @PathVariable Long unitId,
            @Valid @RequestBody AmendRequest request) {
        return workspaceService.amendUnit(caseId, unitId, request.currentSubmissionId(), operator());
    }

    /** 问题处置：解决 / 说明不相关 / 披露未解决（降级不再随处置一步完成，A5-04）。 */
    @PostMapping("/issues/{issueId}/dispositions")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.IssueView dispose(@PathVariable Long caseId, @PathVariable Long issueId,
            @Valid @RequestBody DispositionRequest request) {
        if (injectionGuard.scan(request.reason()).suspicious()) {
            throw new IllegalArgumentException("处置理由包含不被允许的内容");
        }
        return workspaceService.disposeIssue(caseId, issueId, request.expectedRevision(), request.disposition(),
                request.reason(), request.evidenceReference(), operator());
    }

    /** 降级提案第一步（A5-04）：分析员提交拟议降级，等待独立复核人确认。 */
    @PostMapping("/issues/{issueId}/downgrade-proposals")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.IssueReviewView proposeDowngrade(@PathVariable Long caseId, @PathVariable Long issueId,
            @Valid @RequestBody DowngradeProposalRequest request) {
        if (injectionGuard.scan(request.reason()).suspicious()) {
            throw new IllegalArgumentException("降级理由包含不被允许的内容");
        }
        return workspaceService.proposeDowngrade(caseId, issueId, request.expectedRevision(), request.downgradeTo(),
                request.reason(), request.evidenceReference(), operator());
    }

    /** 降级确认第二步（A5-04）：另一位已认证 REVIEWER/ADMIN 独立确认；身份由服务端写入。 */
    @PostMapping("/downgrade-proposals/{proposalId}/confirmations")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public ExplanationViews.IssueView confirmDowngrade(@PathVariable Long caseId, @PathVariable Long proposalId,
            @Valid @RequestBody DowngradeConfirmationRequest request) {
        return workspaceService.confirmDowngrade(caseId, proposalId, request.expectedProposalRevision(),
                request.expectedIssueRevision(), request.confirmNote(), operator());
    }

    /** 拒绝降级提案：问题现状不变。 */
    @PostMapping("/downgrade-proposals/{proposalId}/rejections")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public ExplanationViews.IssueReviewView rejectDowngrade(@PathVariable Long caseId, @PathVariable Long proposalId,
            @Valid @RequestBody DowngradeRejectionRequest request) {
        return workspaceService.rejectDowngrade(caseId, proposalId, request.expectedProposalRevision(),
                request.rejectedReason(), operator());
    }

    /** 分析员按问题提出补件建议（进入待处理复核队列，不产生 REVIEWER 权限）。 */
    @PostMapping("/edd-proposals")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public EnhancedDueDiligenceView proposeEdd(@PathVariable Long caseId,
            @Valid @RequestBody EddProposalRequest request) {
        return eddService.proposeByAnalyst(caseId, request.requiredItems(), request.unitLabel(),
                request.issueBindingsJson(), operator());
    }

    /** 最终复核候选依据：采用单元、未知、任务义务、canExclude/canConfirm 与 reviewBasisToken。 */
    @GetMapping("/review-basis")
    public ExplanationViews.ReviewBasisView reviewBasis(@PathVariable Long caseId) {
        return workspaceService.getReviewBasis(caseId, operator());
    }

    /** 持续核验任务显式完成（不同复核人核对，RESOLVED 不由其他业务决定自动产生）。 */
    @PostMapping("/edd-tasks/{requestId}/completion")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public EnhancedDueDiligenceView completeEddTask(@PathVariable Long caseId, @PathVariable Long requestId,
            @Valid @RequestBody TaskCompletionRequest request) {
        return eddService.completeTask(caseId, requestId, request.expectedRevision(), request.resolutionReason(),
                operator());
    }

    /** Claim 声明（G1-2）：C1~C6 事实状态 + 材料关联（含来源家族）；覆盖式落库并冻结 revision。 */
    @PutMapping("/units/{unitId}/claims")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public List<ExplanationViews.ClaimView> declareClaims(@PathVariable Long caseId, @PathVariable Long unitId,
            @Valid @RequestBody ClaimsRequest request) {
        return claimService.declareClaims(caseId, unitId,
                request.claims() == null ? List.of() : request.claims()
                    .stream()
                    .map(item -> new ExplanationClaimService.ClaimDeclaration(item.claimCode(), item.status(),
                            item.importance(), item.judgement(), item.methodNote(), item.limitations(),
                            item.notApplicableReason(),
                            item.links() == null ? List.of() : item.links()
                                .stream()
                                .map(link -> new ExplanationClaimService.LinkDeclaration(link.artifactVersionId(),
                                        link.direction(), link.location(), link.note()))
                                .toList()))
                    .toList(),
                operator());
    }

    /** 读取单元 Claim 视图（含独立来源计数）。 */
    @GetMapping("/units/{unitId}/claims")
    public List<ExplanationViews.ClaimView> claims(@PathVariable Long caseId, @PathVariable Long unitId) {
        return claimService.viewAll(caseId, unitId);
    }

    /** 定向核验建议（v3 计划 §7）：下一动作按优先级排序；只对缺口发起可解释的补件。 */
    @GetMapping("/units/{unitId}/next-actions")
    public List<ExplanationViews.NextActionView> nextActions(@PathVariable Long caseId, @PathVariable Long unitId) {
        return workspaceService.nextActions(caseId, unitId);
    }

    /** 重复补件提醒：同事实键已有 ≥2 条非 OPEN 处置记录 → 提示替代方式/升级。 */
    @GetMapping("/units/{unitId}/repeated-evidence")
    public RepeatedEvidenceResponse repeatedEvidence(@PathVariable Long caseId, @PathVariable Long unitId,
            @RequestParam String factKey) {
        return new RepeatedEvidenceResponse(workspaceService.repeatedEvidenceRequest(caseId, unitId, factKey));
    }

    /** 供前端展示配方口径（只读）。 */
    @GetMapping("/explanation-policies")
    public ExplanationPoliciesResponse policies() {
        return new ExplanationPoliciesResponse(
                List.of(ExplanationPolicyCatalog.GOODS_SETTLED_V1, ExplanationPolicyCatalog.GOODS_PREPAY_V1,
                        ExplanationPolicyCatalog.GOODS_GROUP_PAYMENT_V1),
                policyCatalog.questionFocus(ExplanationPolicyCatalog.GOODS_SETTLED_V1),
                policyCatalog.questionFocus(ExplanationPolicyCatalog.GOODS_PREPAY_V1),
                policyCatalog.questionFocus(ExplanationPolicyCatalog.GOODS_GROUP_PAYMENT_V1));
    }

    public record CaptureRequest(@NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 500) String sourceReference) {
    }

    public record VerificationRequest(@NotBlank @Size(max = 64) String method,
            @NotBlank @Size(max = 10_000) String observedFacts, @Size(max = 5_000) String limitations,
            @NotBlank @Size(max = 64) String result,
            /** FR-01：核验对象（Q1~Q6 或 Claim 事实键）；空为材料级通用核验。 */
            @Size(max = 128) String subjectFactKey) {
    }

    public record DraftRequest(@PositiveOrZero int expectedDraftRevision,
            @NotBlank @Size(max = 100_000) String draftJson) {
    }

    public record ClaimLinkRequest(@NotNull Long artifactVersionId, @NotBlank @Size(max = 32) String direction,
            @Size(max = 500) String location, @Size(max = 2_000) String note) {
    }

    public record ClaimRequest(@NotBlank @Size(max = 32) String claimCode, @NotBlank @Size(max = 32) String status,
            @NotBlank @Size(max = 32) String importance, @Size(max = 10_000) String judgement,
            @Size(max = 5_000) String methodNote, @Size(max = 5_000) String limitations,
            @Size(max = 2_000) String notApplicableReason, @Size(max = 100) List<@Valid ClaimLinkRequest> links) {
    }

    public record ClaimsRequest(@NotNull @Size(max = 100) List<@Valid ClaimRequest> claims) {
    }

    public record SubmitRequest(@PositiveOrZero int expectedDraftRevision,
            @NotBlank @Size(max = 128) String reviewBasisToken, @NotBlank @Size(max = 128) String idempotencyKey) {
    }

    public record AmendRequest(@NotNull Long currentSubmissionId) {
    }

    public record DispositionRequest(@PositiveOrZero int expectedRevision, @NotBlank @Size(max = 64) String disposition,
            @NotBlank @Size(max = 2_000) String reason, @Size(max = 500) String evidenceReference) {
    }

    public record DowngradeProposalRequest(@PositiveOrZero int expectedRevision,
            @NotBlank @Size(max = 64) String downgradeTo, @NotBlank @Size(max = 2_000) String reason,
            @Size(max = 500) String evidenceReference) {
    }

    public record DowngradeConfirmationRequest(@PositiveOrZero int expectedProposalRevision,
            @PositiveOrZero int expectedIssueRevision, @Size(max = 2_000) String confirmNote) {
    }

    public record DowngradeRejectionRequest(@PositiveOrZero int expectedProposalRevision,
            @NotBlank @Size(max = 2_000) String rejectedReason) {
    }

    public record EddProposalRequest(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 500) String> requiredItems,
            @NotBlank @Size(max = 128) String unitLabel, @Size(max = 20_000) String issueBindingsJson) {
    }

    public record TaskCompletionRequest(@PositiveOrZero int expectedRevision,
            @NotBlank @Size(max = 2_000) String resolutionReason) {
    }

    public record RepeatedEvidenceResponse(boolean repeated) {
    }

    public record ExplanationPoliciesResponse(List<String> policies, Map<String, String> settledQuestions,
            Map<String, String> prepayQuestions, Map<String, String> groupPaymentQuestions) {
    }

}
