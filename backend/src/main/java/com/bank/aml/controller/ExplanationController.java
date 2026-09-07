package com.bank.aml.controller;

import com.bank.aml.explanation.ExplanationPolicyCatalog;
import com.bank.aml.explanation.ExplanationViews;
import com.bank.aml.explanation.ExplanationWorkspaceService;
import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.EnhancedDueDiligenceView;
import com.bank.aml.security.PromptInjectionGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    public ExplanationController(ExplanationWorkspaceService workspaceService,
                                 ExplanationPolicyCatalog policyCatalog,
                                 EnhancedDueDiligenceService eddService,
                                 PromptInjectionGuard injectionGuard) {
        this.workspaceService = workspaceService;
        this.policyCatalog = policyCatalog;
        this.eddService = eddService;
        this.injectionGuard = injectionGuard;
    }

    private String operator() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** 工作区：全部预警单元、范围、六问题、待分派材料及阻断项；返回 caseFactsEpoch。 */
    @GetMapping("/explanation-workspace")
    public ExplanationViews.WorkspaceView workspace(@PathVariable Long caseId) {
        return workspaceService.openWorkspace(caseId);
    }

    /** 抓取受控材料（不透明来源引用 + 声称哈希）；登记 ≠ 已核验。 */
    @PostMapping("/evidence/captures")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.EvidenceView capture(@PathVariable Long caseId,
                                                 @RequestBody CaptureRequest request) {
        return workspaceService.captureEvidence(caseId, request.sourceSystem(), request.sourceReference(),
                request.contentSha256(), request.claimedSha256(), operator());
    }

    /** 记录具体核验动作（方法、观察、限制、结果）。 */
    @PostMapping("/evidence/{versionId}/verifications")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.VerificationView verify(@PathVariable Long caseId, @PathVariable Long versionId,
                                                    @RequestBody VerificationRequest request) {
        if (injectionGuard.scan(request.observedFacts()).suspicious()) {
            throw new IllegalArgumentException("核验事实包含不被允许的内容");
        }
        return workspaceService.recordVerification(caseId, versionId, request.method(),
                request.observedFacts(), request.limitations(), request.result(), operator());
    }

    /** 保存六问题草稿（expectedDraftRevision 乐观锁；不产生最终结论）。 */
    @PutMapping("/units/{unitId}/draft")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.UnitView saveDraft(@PathVariable Long caseId, @PathVariable Long unitId,
                                               @RequestBody DraftRequest request) {
        if (injectionGuard.scan(request.draftJson()).suspicious()) {
            throw new IllegalArgumentException("草稿包含不被允许的内容");
        }
        return workspaceService.saveDraft(caseId, unitId, request.expectedDraftRevision(),
                request.draftJson(), operator());
    }

    /** 原子提交本单元建议与当前覆盖（草稿版本 + 依据令牌 + 幂等键）。 */
    @PostMapping("/units/{unitId}/submissions")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.SubmissionResult submit(@PathVariable Long caseId, @PathVariable Long unitId,
                                                    @RequestBody SubmitRequest request) {
        return workspaceService.submitUnit(caseId, unitId, request.expectedDraftRevision(),
                request.reviewBasisToken(), request.idempotencyKey(), operator());
    }

    /** 修订：撤回当前提交并开始下一草稿（旧提交可回放但不可被最终采用）。 */
    @PostMapping("/units/{unitId}/amendments")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.UnitView amend(@PathVariable Long caseId, @PathVariable Long unitId,
                                           @RequestBody AmendRequest request) {
        return workspaceService.amendUnit(caseId, unitId, request.currentSubmissionId(), operator());
    }

    /** 问题处置：解决 / 说明不相关 / 披露未解决；重要性降级需不同复核人确认。 */
    @PostMapping("/issues/{issueId}/dispositions")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ExplanationViews.IssueView dispose(@PathVariable Long caseId, @PathVariable Long issueId,
                                              @RequestBody DispositionRequest request) {
        if (injectionGuard.scan(request.reason()).suspicious()) {
            throw new IllegalArgumentException("处置理由包含不被允许的内容");
        }
        return workspaceService.disposeIssue(caseId, issueId, request.expectedRevision(),
                request.disposition(), request.reason(), request.evidenceReference(),
                request.downgradeTo(), request.confirmedBy(), operator());
    }

    /** 分析员按问题提出补件建议（进入待处理复核队列，不产生 REVIEWER 权限）。 */
    @PostMapping("/edd-proposals")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public EnhancedDueDiligenceView proposeEdd(@PathVariable Long caseId,
                                               @RequestBody EddProposalRequest request) {
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
                                                    @RequestBody TaskCompletionRequest request) {
        return eddService.completeTask(caseId, requestId, request.expectedRevision(),
                request.resolutionReason(), operator());
    }

    /** 供前端展示配方口径（只读）。 */
    @GetMapping("/explanation-policies")
    public Map<String, Object> policies() {
        return Map.of(
                "policies", List.of(ExplanationPolicyCatalog.GOODS_SETTLED_V1,
                        ExplanationPolicyCatalog.GOODS_PREPAY_V1),
                "settledQuestions", policyCatalog.questionFocus(ExplanationPolicyCatalog.GOODS_SETTLED_V1),
                "prepayQuestions", policyCatalog.questionFocus(ExplanationPolicyCatalog.GOODS_PREPAY_V1));
    }

    public record CaptureRequest(String sourceSystem, String sourceReference, String contentSha256,
                                 String claimedSha256) {
    }

    public record VerificationRequest(String method, String observedFacts, String limitations, String result) {
    }

    public record DraftRequest(int expectedDraftRevision, String draftJson) {
    }

    public record SubmitRequest(int expectedDraftRevision, String reviewBasisToken, String idempotencyKey) {
    }

    public record AmendRequest(Long currentSubmissionId) {
    }

    public record DispositionRequest(int expectedRevision, String disposition, String reason,
                                     String evidenceReference, String downgradeTo, String confirmedBy) {
    }

    public record EddProposalRequest(List<String> requiredItems, String unitLabel, String issueBindingsJson) {
    }

    public record TaskCompletionRequest(int expectedRevision, String resolutionReason) {
    }
}
