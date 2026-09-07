package com.bank.aml.review;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.security.UserAccount;
import com.bank.aml.security.UserAccountRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.Locale;

/** 补充尽调任务的创建、材料提交和复核消费闭环。 */
@Service
public class EnhancedDueDiligenceService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final Set<String> REQUIRED_ITEM_CODES = Set.of(
            "CUSTOMER_IDENTITY", "BENEFICIAL_OWNER", "SOURCE_OF_FUNDS", "TRANSACTION_PURPOSE",
            "COUNTERPARTY_RELATIONSHIP", "SUPPORTING_CONTRACT_INVOICE", "WATCHLIST_IDENTITY");
    private static final Set<String> EVIDENCE_SOURCE_SYSTEMS = Set.of(
            "KYC_PLATFORM", "CORE_BANKING", "DOCUMENT_MANAGEMENT", "SANCTIONS_SCREENING",
            "CUSTOMER_PROVIDED");
    private static final Pattern SOURCE_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");

    private final EnhancedDueDiligenceRequestRepository repository;
    private final EnhancedDueDiligenceEvidenceRepository evidenceRepository;
    private final CaseRepository caseRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditOutboxService auditOutbox;
    private final ObjectMapper objectMapper;

    public EnhancedDueDiligenceService(EnhancedDueDiligenceRequestRepository repository,
                                       EnhancedDueDiligenceEvidenceRepository evidenceRepository,
                                       CaseRepository caseRepository,
                                       UserAccountRepository userAccountRepository,
                                       AuditOutboxService auditOutbox,
                                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.evidenceRepository = evidenceRepository;
        this.caseRepository = caseRepository;
        this.userAccountRepository = userAccountRepository;
        this.auditOutbox = auditOutbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<EnhancedDueDiligenceView> list(Long caseId) {
        requireCase(caseId);
        return repository.findByCaseIdOrderByRoundNoAsc(caseId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<EnhancedDueDiligenceRequest> latest(Long caseId) {
        return repository.findTopByCaseIdOrderByRoundNoDesc(caseId);
    }

    @Transactional(readOnly = true)
    public List<EnhancedDueDiligenceView> pendingTasks(String operator, boolean allTasks) {
        List<EnhancedDueDiligenceRequest> tasks = allTasks
                ? repository.findByStatusOrderByDueAtAsc(EnhancedDueDiligenceStatus.OPEN)
                : repository.findByAssignedToAndStatusOrderByDueAtAsc(
                        operator, EnhancedDueDiligenceStatus.OPEN);
        return tasks.stream().map(this::view).toList();
    }

    /** 在更新案件状态前验证，避免产生无法履行或重复的补充尽调任务。
     *  v2（§8）：OPEN 的 DECISION_SUPPORT 任务阻断最终处置；CONTINUING_REVIEW 不阻断；
     *  确认可疑且需义务接续时，转移在同一事务内完成（见 applyReviewDecision）。 */
    public void validateReviewDecision(Long caseId, ReviewDecision decision,
                                       List<String> requiredItems, LocalDateTime dueAt,
                                       String assignedTo, String assignedUnit) {
        List<EnhancedDueDiligenceRequest> openTasks = repository
                .findByCaseIdAndStatusOrderByIdAsc(caseId, EnhancedDueDiligenceStatus.OPEN);
        for (EnhancedDueDiligenceRequest task : openTasks) {
            if (task.getPurpose() == com.bank.aml.explanation.EddTaskPurpose.DECISION_SUPPORT) {
                throw new IllegalStateException("第 " + task.getRoundNo() + " 轮补充尽调（决策支持）尚未提交，不能再次处置；"
                        + "确认可疑路径需在同一事务内完成义务接续");
            }
        }
        if (decision == ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) {
            normalizeRequiredItems(requiredItems);
            validateDueAt(dueAt);
            validateAssignment(assignedTo, assignedUnit);
        }
    }

    /** 案件条件更新成功后调用；与人工复核记录处于同一事务。
     *  v2（§8.2）：不再把最近一轮 SUBMITTED 自动置为 RESOLVED（后续核验由不同复核人明确完成）；
     *  确认可疑需义务接续时，在同一事务内：创建 CONTINUING_REVIEW 任务 + 将被接替的
     *  OPEN DECISION_SUPPORT 任务置为 CANCELLED（原因 SUPERSEDED_BY_CONTINUING_REVIEW），
     *  任一写入失败则整体回滚。 */
    public void applyReviewDecision(Long caseId, ReviewDecision decision, ReviewReasonCode reason,
                                    List<String> requiredItems, LocalDateTime dueAt,
                                    String assignedTo, String assignedUnit,
                                    String requestedBy, LocalDateTime decidedAt) {
        applyReviewDecision(caseId, decision, reason, requiredItems, dueAt, assignedTo, assignedUnit,
                requestedBy, decidedAt, null, null);
    }

    /** 带义务接续计划的版本：continuationTasks 非空时在同一事务内接续。 */
    public void applyReviewDecision(Long caseId, ReviewDecision decision, ReviewReasonCode reason,
                                    List<String> requiredItems, LocalDateTime dueAt,
                                    String assignedTo, String assignedUnit,
                                    String requestedBy, LocalDateTime decidedAt,
                                    List<ContinuationTaskPlan> continuationTasks,
                                    Long originReviewId) {
        if (continuationTasks != null && !continuationTasks.isEmpty()) {
            if (decision != ReviewDecision.CONFIRM_SUSPICIOUS) {
                throw new IllegalArgumentException("义务接续仅支持确认可疑路径；当前关键未知不能借接续放行排除");
            }
            transferObligations(caseId, requestedBy, decidedAt, continuationTasks, originReviewId);
        }
        if (decision != ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) {
            return;
        }
        Optional<EnhancedDueDiligenceRequest> latest = repository.findTopByCaseIdOrderByRoundNoDesc(caseId);
        int nextRound = latest.map(request -> request.getRoundNo() + 1).orElse(1);
        EnhancedDueDiligenceRequest request = new EnhancedDueDiligenceRequest();
        request.setCaseId(caseId);
        request.setRoundNo(nextRound);
        request.setReasonCode(reason.name());
        request.setRequiredItemsJson(writeList(normalizeRequiredItems(requiredItems)));
        request.setRequestedBy(requestedBy);
        request.setRequestedAt(decidedAt);
        request.setAssignedTo(assignedTo.trim());
        request.setAssignedUnit(assignedUnit.trim());
        request.setDueAt(dueAt);
        request.setStatus(EnhancedDueDiligenceStatus.OPEN);
        request.setPurpose(com.bank.aml.explanation.EddTaskPurpose.DECISION_SUPPORT);
        repository.save(request);
    }

    /** 义务接续（§8.2.2—3）：为继续核验创建 CONTINUING_REVIEW 任务；被接替的原任务置 CANCELLED（非 RESOLVED）。
     *  在最终复核事务内先行调用；任一写入失败整体回滚。 */
    public void transferObligations(Long caseId, String reviewer, LocalDateTime decidedAt,
                                     List<ContinuationTaskPlan> plans, Long originReviewId) {
        for (ContinuationTaskPlan plan : plans) {
            if (plan.completionStandard() == null || plan.completionStandard().trim().length() < 10) {
                throw new IllegalArgumentException("接续任务必须写明完成标准（核验目标与验收口径，至少 10 个字符）");
            }
            validateAssignment(plan.assignedTo(), plan.assignedUnit());
            validateDueAt(plan.dueAt());
        }
        for (ContinuationTaskPlan plan : plans) {
            Optional<EnhancedDueDiligenceRequest> latest = repository.findTopByCaseIdOrderByRoundNoDesc(caseId);
            int nextRound = latest.map(request -> request.getRoundNo() + 1).orElse(1);
            EnhancedDueDiligenceRequest task = new EnhancedDueDiligenceRequest();
            task.setCaseId(caseId);
            task.setRoundNo(nextRound);
            task.setReasonCode("CONTINUING_REVIEW");
            // CONTINUING_REVIEW 的“材料清单”为完成标准所需的具体核验动作；绑定原任务与问题。
            task.setRequiredItemsJson(writeList(plan.requiredItems() == null || plan.requiredItems().isEmpty()
                    ? List.of("TRANSACTION_PURPOSE") : plan.requiredItems()));
            task.setRequestedBy(reviewer);
            task.setRequestedAt(decidedAt);
            task.setAssignedTo(plan.assignedTo().trim());
            task.setAssignedUnit(plan.assignedUnit().trim());
            task.setDueAt(plan.dueAt());
            task.setStatus(EnhancedDueDiligenceStatus.OPEN);
            task.setPurpose(com.bank.aml.explanation.EddTaskPurpose.CONTINUING_REVIEW);
            task.setOriginRequestId(plan.originRequestId());
            task.setOriginReviewId(originReviewId);
            task.setIssueBindings(plan.issueBindingsJson());
            task.setDueCalendarVersion(com.bank.aml.explanation.ExplanationWorkspaceService.DEFAULT_CALENDAR_VERSION);
            repository.save(task);
            auditOutbox.enqueue("EDD_CONTINUATION:" + caseId + ":" + task.getRoundNo(),
                    reviewer, "EDD_CONTINUING_REVIEW_CREATED", "EDD_REQUEST", String.valueOf(task.getId()),
                    "caseId=" + caseId + ",originRequestId=" + plan.originRequestId()
                            + ",standardLen=" + plan.completionStandard().trim().length());
        }
        // 被接替的 OPEN DECISION_SUPPORT 任务：CANCELLED + SUPERSEDED_BY_CONTINUING_REVIEW，保留原轮次与未解决问题。
        for (EnhancedDueDiligenceRequest open : repository
                .findByCaseIdAndStatusOrderByIdAsc(caseId, EnhancedDueDiligenceStatus.OPEN)) {
            if (open.getPurpose() != com.bank.aml.explanation.EddTaskPurpose.DECISION_SUPPORT) {
                continue;
            }
            open.setStatus(EnhancedDueDiligenceStatus.CANCELLED);
            open.setRevision(open.getRevision() + 1);
            open.setCancelledBy(reviewer);
            open.setCancelledAt(decidedAt);
            open.setCancellationReason("已由持续核验任务接替；原义务未消失，由 CONTINUING_REVIEW 履行");
            open.setResolutionReason("SUPERSEDED_BY_CONTINUING_REVIEW");
            repository.save(open);
            auditOutbox.enqueue("EDD_SUPERSEDED:" + caseId + ":" + open.getId(),
                    reviewer, "EDD_DECISION_SUPPORT_SUPERSEDED", "EDD_REQUEST", String.valueOf(open.getId()),
                    "caseId=" + caseId + ",round=" + open.getRoundNo());
        }
    }

    /** 义务接续计划项（v2 计划 §8.2/§13 continuationPlan）。 */
    public record ContinuationTaskPlan(
            Long originRequestId,
            String assignedTo,
            String assignedUnit,
            LocalDateTime dueAt,
            List<String> requiredItems,
            String completionStandard,
            String issueBindingsJson
    ) {
    }

    @Transactional
    public EnhancedDueDiligenceView submitResponse(Long caseId, Long requestId, int expectedRevision,
                                                    String responseSummary,
                                                    List<EnhancedDueDiligenceEvidenceSubmission> evidenceItems,
                                                    String respondedBy, boolean adminOverride) {
        CaseEntity caseEntity = requireCaseForUpdate(caseId);
        EnhancedDueDiligenceRequest request = repository.findByIdAndCaseId(requestId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("补充尽调任务不存在"));
        // §8.3：持续核验任务在 HOLD/REPORT_PENDING/DONE 均可按任务权限提交（任务仍 OPEN）；
        // 决策支持补件仍限 HOLD。该权限只写任务与补充材料，不开放最终决定编辑。
        if (request.getPurpose() == com.bank.aml.explanation.EddTaskPurpose.CONTINUING_REVIEW) {
            if (!Set.of(CaseStatus.HOLD, CaseStatus.REPORT_PENDING, CaseStatus.DONE)
                    .contains(caseEntity.getStatus())) {
                throw new IllegalStateException("持续核验任务只能在人工复核/待报送/已结案状态下提交材料");
            }
        } else if (caseEntity.getStatus() != CaseStatus.HOLD) {
            throw new IllegalStateException("案件不在人工复核状态，不能提交补充尽调材料");
        }
        String summary = responseSummary == null ? "" : responseSummary.trim();
        if (summary.length() < 10 || summary.length() > 2000) {
            throw new IllegalArgumentException("材料说明需为 10 ~ 2000 个字符");
        }
        if (request.getStatus() != EnhancedDueDiligenceStatus.OPEN
                || request.getRevision() != expectedRevision) {
            throw new IllegalStateException("补充尽调任务已被提交或版本已变化，请刷新后重试");
        }
        if (!adminOverride && !respondedBy.equals(request.getAssignedTo())) {
            throw new IllegalStateException("仅该任务承办人可以提交补充尽调材料");
        }
        List<String> requiredItems = readList(request.getRequiredItemsJson());
        List<EnhancedDueDiligenceEvidenceSubmission> normalizedEvidence =
                normalizeEvidenceItems(evidenceItems, requiredItems);
        List<EnhancedDueDiligenceEvidence> savedEvidence = evidenceRepository.saveAllAndFlush(
                normalizedEvidence.stream().map(item -> evidenceEntity(
                        caseId, requestId, item, respondedBy)).toList());
        List<String> references = savedEvidence.stream().map(this::evidenceId).toList();
        int updated = repository.submitResponse(requestId, caseId,
                EnhancedDueDiligenceStatus.OPEN, EnhancedDueDiligenceStatus.SUBMITTED,
                expectedRevision, summary, writeList(references), respondedBy, LocalDateTime.now());
        if (updated == 0) {
            throw new IllegalStateException("补充尽调任务已被提交或版本已变化，请刷新后重试");
        }
        auditOutbox.enqueue("EDD_SUBMIT:" + requestId + ":" + expectedRevision,
                respondedBy, "EDD_MATERIAL_SUBMIT", "EDD_REQUEST", String.valueOf(requestId),
                "caseId=" + caseId + ",round=" + request.getRoundNo()
                        + ",evidenceCount=" + references.size() + ",summaryLen=" + summary.length());
        return view(repository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("补充尽调任务不存在")));
    }

    /**
     * v2（§13 POST /edd-proposals）：分析员按问题提出补件建议。
     * 建议以 OPEN 的 DECISION_SUPPORT 任务进入待处理复核队列；不产生 REVIEWER 权限，
     * 也不直接阻断最终处置（由 REVIEWER 在最终复核中确认/调整/撤销）。
     */
    @Transactional
    public EnhancedDueDiligenceView proposeByAnalyst(Long caseId, List<String> requiredItems,
                                                     String targetUnitLabel, String issueBindingsJson,
                                                     String proposedBy) {
        requireCase(caseId);
        List<String> normalized = normalizeRequiredItems(requiredItems);
        Optional<EnhancedDueDiligenceRequest> latest = repository.findTopByCaseIdOrderByRoundNoDesc(caseId);
        int nextRound = latest.map(request -> request.getRoundNo() + 1).orElse(1);
        EnhancedDueDiligenceRequest request = new EnhancedDueDiligenceRequest();
        request.setCaseId(caseId);
        request.setRoundNo(nextRound);
        request.setReasonCode("ANALYST_PROPOSAL");
        request.setRequiredItemsJson(writeList(normalized));
        request.setRequestedBy(proposedBy);
        request.setRequestedAt(LocalDateTime.now());
        request.setAssignedTo("REVIEWER_QUEUE");
        request.setAssignedUnit("人工复核队列");
        request.setDueAt(LocalDateTime.now().plus(14, ChronoUnit.DAYS));
        request.setStatus(EnhancedDueDiligenceStatus.OPEN);
        request.setPurpose(com.bank.aml.explanation.EddTaskPurpose.DECISION_SUPPORT);
        request.setIssueBindings(issueBindingsJson == null ? null : issueBindingsJson.trim());
        repository.save(request);
        auditOutbox.enqueue("EDD_PROPOSAL:" + caseId + ":" + request.getId(),
                proposedBy, "EDD_PROPOSAL_CREATED", "EDD_REQUEST", String.valueOf(request.getId()),
                "caseId=" + caseId + ",items=" + normalized.size()
                        + (targetUnitLabel == null ? "" : ",unit=" + targetUnitLabel));
        return view(request);
    }

    /**
     * v2（§8.3）：明确完成任务——由（不同于原作者的）复核人确认持续核验已完成。
     * RESOLVED 不再由其他业务决定自动产生（原 applyReviewDecision 的自动 RESOLVED 已移除）。
     */
    @Transactional
    public EnhancedDueDiligenceView completeTask(Long caseId, Long requestId, int expectedRevision,
                                                 String resolutionReason, String resolvedBy) {
        requireCase(caseId);
        EnhancedDueDiligenceRequest request = repository.findByIdAndCaseId(requestId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("补充尽调任务不存在"));
        if (request.getStatus() != EnhancedDueDiligenceStatus.SUBMITTED) {
            throw new IllegalStateException("仅已提交材料待核验（SUBMITTED）的任务可以被明确完成");
        }
        String reason = resolutionReason == null ? "" : resolutionReason.trim();
        if (reason.length() < 10) {
            throw new IllegalArgumentException("完成核验需记录核验结论与依据（至少 10 个字符）");
        }
        request.setStatus(EnhancedDueDiligenceStatus.RESOLVED);
        request.setRevision(request.getRevision() + 1);
        request.setResolvedAt(LocalDateTime.now());
        request.setResolutionReason(reason);
        EnhancedDueDiligenceRequest saved = repository.save(request);
        auditOutbox.enqueue("EDD_COMPLETE:" + caseId + ":" + requestId + ":" + expectedRevision,
                resolvedBy, "EDD_TASK_COMPLETED", "EDD_REQUEST", String.valueOf(requestId),
                "caseId=" + caseId + ",round=" + request.getRoundNo()
                        + ",purpose=" + request.getPurpose());
        return view(saved);
    }

    EnhancedDueDiligenceView view(EnhancedDueDiligenceRequest request) {
        return new EnhancedDueDiligenceView(request.getId(), request.getCaseId(), request.getRoundNo(),
                request.getReasonCode(), readList(request.getRequiredItemsJson()), request.getRequestedBy(),
                request.getRequestedAt(), request.getAssignedTo(), request.getAssignedUnit(), request.getDueAt(),
                request.getStatus(), isOverdue(request), request.getRevision(),
                request.getResponseSummary(), readList(request.getEvidenceReferencesJson()), request.getRespondedBy(),
                request.getRespondedAt(), request.getResolvedAt(), request.getCancelledBy(), request.getCancelledAt(),
                request.getCancellationReason(),
                evidenceRepository.findByRequestIdOrderByIdAsc(request.getId()).stream()
                        .map(this::evidenceView).toList());
    }

    @Transactional
    public EnhancedDueDiligenceView cancel(Long caseId, Long requestId, int expectedRevision,
                                           String reason, String cancelledBy) {
        CaseEntity caseEntity = requireCaseForUpdate(caseId);
        EnhancedDueDiligenceRequest request = repository.findByIdAndCaseId(requestId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("补充尽调任务不存在"));
        // 决策支持补件仍限 HOLD；持续核验任务的撤销是复核动作，与父案状态分离（§17.2 停止控制分离）。
        if (request.getPurpose() != com.bank.aml.explanation.EddTaskPurpose.CONTINUING_REVIEW
                && caseEntity.getStatus() != CaseStatus.HOLD) {
            throw new IllegalStateException("案件不在人工复核状态，不能撤销补充尽调任务");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 10 || normalizedReason.length() > 500) {
            throw new IllegalArgumentException("撤销原因需为 10 ~ 500 个字符");
        }
        if (request.getStatus() != EnhancedDueDiligenceStatus.OPEN
                || request.getRevision() != expectedRevision) {
            throw new IllegalStateException("仅可撤销当前待补充任务，或任务版本已变化");
        }
        request.setStatus(EnhancedDueDiligenceStatus.CANCELLED);
        request.setRevision(request.getRevision() + 1);
        request.setCancelledBy(cancelledBy);
        request.setCancelledAt(LocalDateTime.now());
        request.setCancellationReason(normalizedReason);
        if (request.getPurpose() == com.bank.aml.explanation.EddTaskPurpose.CONTINUING_REVIEW) {
            request.setResolutionReason("CANCELLED_BY_REVIEWER");
        }
        EnhancedDueDiligenceRequest saved = repository.save(request);
        auditOutbox.enqueue("EDD_CANCEL:" + requestId + ":" + expectedRevision,
                cancelledBy, "EDD_REQUEST_CANCEL", "EDD_REQUEST", String.valueOf(requestId),
                "caseId=" + caseId + ",round=" + request.getRoundNo()
                        + ",reasonLen=" + normalizedReason.length());
        return view(saved);
    }

    private CaseEntity requireCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private CaseEntity requireCaseForUpdate(Long caseId) {
        return caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private List<String> normalizeRequiredItems(List<String> values) {
        if (values == null) throw new IllegalArgumentException("请选择需要补充的材料");
        List<String> normalized = new LinkedHashSet<>(values.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase())
                .filter(value -> !value.isBlank()).toList()).stream().toList();
        if (normalized.isEmpty() || normalized.size() > 5 || !REQUIRED_ITEM_CODES.containsAll(normalized)) {
            throw new IllegalArgumentException("补充材料需从允许项中选择 1 ~ 5 项");
        }
        return normalized;
    }

    private void validateDueAt(LocalDateTime dueAt) {
        LocalDateTime now = LocalDateTime.now();
        if (dueAt == null || !dueAt.isAfter(now) || dueAt.isAfter(now.plus(90, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("补充尽调截止时间必须在未来 90 天内");
        }
    }

    private void validateAssignment(String assignedTo, String assignedUnit) {
        String assignee = assignedTo == null ? "" : assignedTo.trim();
        String unit = assignedUnit == null ? "" : assignedUnit.trim();
        if (unit.length() < 2 || unit.length() > 64 || unit.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("承办部门需为 2 ~ 64 个字符");
        }
        UserAccount account = userAccountRepository.findByUsername(assignee)
                .filter(UserAccount::isEnabled)
                .filter(user -> Set.of("ANALYST", "ADMIN").contains(user.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("承办人不存在、已停用或没有分析员权限"));
        if (!account.getUsername().equals(assignee)) {
            throw new IllegalArgumentException("承办人用户名不匹配");
        }
    }

    private boolean isOverdue(EnhancedDueDiligenceRequest request) {
        return request.getStatus() == EnhancedDueDiligenceStatus.OPEN
                && request.getDueAt() != null && request.getDueAt().isBefore(LocalDateTime.now());
    }

    private List<EnhancedDueDiligenceEvidenceSubmission> normalizeEvidenceItems(
            List<EnhancedDueDiligenceEvidenceSubmission> values, List<String> requiredItems) {
        if (values == null || values.isEmpty() || values.size() > 20) {
            throw new IllegalArgumentException("证据元数据需为 1 ~ 20 项");
        }
        List<EnhancedDueDiligenceEvidenceSubmission> normalized = values.stream().map(value -> {
            if (value == null) throw new IllegalArgumentException("证据元数据不能为空");
            String itemCode = normalizedUpper(value.requiredItemCode());
            String sourceSystem = normalizedUpper(value.sourceSystem());
            String sourceReference = value.sourceReference() == null ? "" : value.sourceReference().trim();
            String contentSha256 = value.contentSha256() == null ? "" : value.contentSha256().trim().toLowerCase();
            if (!requiredItems.contains(itemCode)) {
                throw new IllegalArgumentException("证据材料项不在本轮补充清单中：" + itemCode);
            }
            if (!EVIDENCE_SOURCE_SYSTEMS.contains(sourceSystem)) {
                throw new IllegalArgumentException("证据来源系统不受信任：" + sourceSystem);
            }
            if (!SOURCE_REFERENCE.matcher(sourceReference).matches()) {
                throw new IllegalArgumentException("来源记录编号格式非法");
            }
            if (!SHA256.matcher(contentSha256).matches()) {
                throw new IllegalArgumentException("证据内容摘要必须为 SHA-256");
            }
            return new EnhancedDueDiligenceEvidenceSubmission(
                    itemCode, sourceSystem, sourceReference, contentSha256);
        }).toList();
        Set<String> uniqueSources = new LinkedHashSet<>();
        for (EnhancedDueDiligenceEvidenceSubmission item : normalized) {
            if (!uniqueSources.add(item.sourceSystem() + "|" + item.sourceReference())) {
                throw new IllegalArgumentException("同一来源记录不能重复提交");
            }
        }
        Set<String> coveredItems = new LinkedHashSet<>(normalized.stream()
                .map(EnhancedDueDiligenceEvidenceSubmission::requiredItemCode).toList());
        List<String> missing = requiredItems.stream().filter(item -> !coveredItems.contains(item)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下补充材料尚无证据覆盖：" + String.join(",", missing));
        }
        return normalized;
    }

    private String normalizedUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private EnhancedDueDiligenceEvidence evidenceEntity(
            Long caseId, Long requestId, EnhancedDueDiligenceEvidenceSubmission item, String capturedBy) {
        EnhancedDueDiligenceEvidence entity = new EnhancedDueDiligenceEvidence();
        entity.setCaseId(caseId);
        entity.setRequestId(requestId);
        entity.setRequiredItemCode(item.requiredItemCode());
        entity.setSourceSystem(item.sourceSystem());
        entity.setSourceReference(item.sourceReference());
        entity.setContentSha256(item.contentSha256());
        entity.setCapturedBy(capturedBy);
        entity.setCapturedAt(LocalDateTime.now());
        return entity;
    }

    private EnhancedDueDiligenceEvidenceView evidenceView(EnhancedDueDiligenceEvidence evidence) {
        return new EnhancedDueDiligenceEvidenceView(evidence.getId(), evidenceId(evidence),
                evidence.getRequiredItemCode(), evidence.getSourceSystem(), evidence.getSourceReference(),
                evidence.getContentSha256(), evidence.getCapturedBy(), evidence.getCapturedAt());
    }

    private String evidenceId(EnhancedDueDiligenceEvidence evidence) {
        return "EDD-EVIDENCE-" + evidence.getId();
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("补充尽调字段序列化失败", e);
        }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<String> result = objectMapper.readValue(value, STRING_LIST);
            return result == null ? List.of() : List.copyOf(result);
        } catch (JsonProcessingException e) {
            // 证据链字段损坏不能伪装成“没有证据”，必须显式暴露数据完整性故障。
            throw new IllegalStateException("补充尽调历史字段损坏", e);
        }
    }
}
