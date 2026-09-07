package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertCoverageConclusion;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.AlertInvestigationCoverage;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.HypothesisStatus;
import com.bank.aml.investigation.InvestigationHypothesis;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.review.ReviewDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 解释核验工作区服务（v2 计划 §3—§10）。
 *
 * <p>核心不变式：
 * <ul>
 *   <li>核验和结论先落到逐预警单元；假设是同场景汇总，允许同案 EXPLAINED 与 SUSPICIOUS 并存；</li>
 *   <li>提交不可变（submissionId + inputDigest）；最终决定只能采用 CURRENT 状态提交；</li>
 *   <li>caseFactsEpoch + reviewBasisToken 控制最终提交：令牌在案件锁内与当前事实比较；</li>
 *   <li>实质贡献人由服务端从操作记录派生，最终复核人不得在贡献人并集内（防自审）；</li>
 *   <li>INTEGRITY_BLOCKER 不允许降级或以业务文字豁免；未评估问题默认按关键待处理；</li>
 *   <li>预付款 NOT_YET_DUE 与已逾期用注入 Clock 的评估日区分；逾期产生关键问题。</li>
 * </ul>
 */
@Service
public class ExplanationWorkspaceService implements ExplanationReadinessPort {

    private static final Set<String> TRUSTED_SOURCE_SYSTEMS = Set.of(
            "KYC_PLATFORM", "CORE_BANKING", "DOCUMENT_MANAGEMENT", "SANCTIONS_SCREENING",
            "CUSTOMER_PROVIDED", "TAX_PLATFORM", "LOGISTICS_PLATFORM");
    private static final Set<String> QUESTION_CODES = Set.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6");
    private static final Set<String> AVAILABILITY = Set.of("RESOLVED", "UNAVAILABLE", "NOT_FOUND", "FORBIDDEN");
    private static final Set<String> INTEGRITY = Set.of("MATCH", "MISMATCH", "NOT_CHECKED");
    private static final Set<String> VERIFICATION_RESULTS = Set.of("CONFIRMED", "MISMATCH", "UNRESOLVED");
    private static final String SCOPE_ISSUE_KEY = "ALERT_SCOPE_UNRESOLVED";
    /** 演示政策的工作日历版本；不同日历不得改变历史截止时间（§6.2）。 */
    public static final String DEFAULT_CALENDAR_VERSION = "CN-WORKDAY-DEMO-2026";

    private final CaseRepository caseRepository;
    private final AlertExplanationUnitRepository unitRepository;
    private final ExplanationSubmissionRepository submissionRepository;
    private final ExplanationIssueRepository issueRepository;
    private final VerificationBasisRepository basisRepository;
    private final EvidenceArtifactVersionRepository artifactRepository;
    private final EvidenceVerificationEventRepository verificationRepository;
    private final ExplanationEvidenceUseRepository evidenceUseRepository;
    private final AlertInvestigationCoverageRepository coverageRepository;
    private final InvestigationHypothesisRepository hypothesisRepository;
    private final com.bank.aml.investigation.AmlAlertRepository alertRepository;
    private final EnhancedDueDiligenceRequestRepository eddRepository;
    private final ExplanationPolicyCatalog policyCatalog;
    private final AuditOutboxService auditOutbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ExplanationWorkspaceService(CaseRepository caseRepository,
                                       AlertExplanationUnitRepository unitRepository,
                                       ExplanationSubmissionRepository submissionRepository,
                                       ExplanationIssueRepository issueRepository,
                                       VerificationBasisRepository basisRepository,
                                       EvidenceArtifactVersionRepository artifactRepository,
                                       EvidenceVerificationEventRepository verificationRepository,
                                       ExplanationEvidenceUseRepository evidenceUseRepository,
                                       AlertInvestigationCoverageRepository coverageRepository,
                                       InvestigationHypothesisRepository hypothesisRepository,
                                       com.bank.aml.investigation.AmlAlertRepository alertRepository,
                                       EnhancedDueDiligenceRequestRepository eddRepository,
                                       ExplanationPolicyCatalog policyCatalog,
                                       AuditOutboxService auditOutbox,
                                       ObjectMapper objectMapper) {
        this(caseRepository, unitRepository, submissionRepository, issueRepository, basisRepository,
                artifactRepository, verificationRepository, evidenceUseRepository, coverageRepository,
                hypothesisRepository, alertRepository, eddRepository, policyCatalog, auditOutbox,
                objectMapper, Clock.systemDefaultZone());
    }

    ExplanationWorkspaceService(CaseRepository caseRepository,
                                AlertExplanationUnitRepository unitRepository,
                                ExplanationSubmissionRepository submissionRepository,
                                ExplanationIssueRepository issueRepository,
                                VerificationBasisRepository basisRepository,
                                EvidenceArtifactVersionRepository artifactRepository,
                                EvidenceVerificationEventRepository verificationRepository,
                                ExplanationEvidenceUseRepository evidenceUseRepository,
                                AlertInvestigationCoverageRepository coverageRepository,
                                InvestigationHypothesisRepository hypothesisRepository,
                                com.bank.aml.investigation.AmlAlertRepository alertRepository,
                                EnhancedDueDiligenceRequestRepository eddRepository,
                                ExplanationPolicyCatalog policyCatalog,
                                AuditOutboxService auditOutbox,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.caseRepository = caseRepository;
        this.unitRepository = unitRepository;
        this.submissionRepository = submissionRepository;
        this.issueRepository = issueRepository;
        this.basisRepository = basisRepository;
        this.artifactRepository = artifactRepository;
        this.verificationRepository = verificationRepository;
        this.evidenceUseRepository = evidenceUseRepository;
        this.coverageRepository = coverageRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.alertRepository = alertRepository;
        this.eddRepository = eddRepository;
        this.policyCatalog = policyCatalog;
        this.auditOutbox = auditOutbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ==================== 工作区 ====================

    @Transactional(readOnly = true)
    public ExplanationViews.WorkspaceView openWorkspace(Long caseId) {
        CaseEntity caseEntity = requireCase(caseId);
        if (caseEntity.getInvestigationContractVersion() < 2) {
            throw new IllegalArgumentException("该案件未启用合理解释核验政策（v2）");
        }
        List<ExplanationViews.UnitView> units = unitRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(this::unitView).toList();
        List<ExplanationViews.IssueView> issues = issueRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(this::issueView).toList();
        List<ExplanationViews.EvidenceView> artifacts = artifactRepository
                .findByCaseIdOrderByCapturedAtAsc(caseId).stream().map(this::evidenceView).toList();
        InvestigationReadinessResult readiness = evaluateReadiness(caseEntity, null);
        return new ExplanationViews.WorkspaceView(caseId, caseEntity.getInvestigationContractVersion(),
                caseEntity.getCaseFactsEpoch(), false, units, issues, artifacts,
                readiness.generalBlockers(), readiness.canExclude(), readiness.canConfirm(),
                readiness.confirmBlockers(), readiness.excludeBlockers());
    }

    // ==================== 单元生命周期（归并/拆分联动） ====================

    /** 预警归并时确保单元存在；范围未映射时登记 ALERT_SCOPE_UNRESOLVED 关键问题。 */
    @Transactional
    public void ensureUnitsForLinkedAlerts(Long caseId, List<AmlAlert> linkedAlerts, String operator) {
        boolean created = false;
        for (AmlAlert alert : linkedAlerts) {
            if (unitRepository.findByCaseIdAndAlertId(caseId, alert.getId()).isPresent()) {
                continue;
            }
            AlertExplanationUnit unit = new AlertExplanationUnit();
            unit.setCaseId(caseId);
            unit.setAlertId(alert.getId());
            coverageRepository.findByAlertId(alert.getId())
                .filter(item -> caseId.equals(item.getCaseId()))
                .ifPresent(coverage -> unit.setHypothesisId(coverage.getHypothesisId()));
            unit.setScopeRevision(1);
            unit.setCreatedBy(operator);
            unitRepository.save(unit);
            ensureIssue(caseId, unit.getId(), SCOPE_ISSUE_KEY + ":" + unit.getId(),
                    IssueSeverity.DECISION_CRITICAL, null,
                    "预警 " + alertLabel(alert) + " 的监测输入映射尚未冻结：命中交易集合未定位，"
                            + "人工挑选的交易不能默认视为完整命中集合，需说明枚举方法后解决该问题。",
                    operator);
            created = true;
        }
        if (created) {
            bumpEpoch(caseId, "UNIT_SCOPE_CREATED");
        }
    }

    /** 预警拆走时撤回其单元的当前提交并删除单元（范围更正，来源为拆分操作）。 */
    @Transactional
    public void removeUnitForAlert(Long caseId, Long alertId, String reason, String operator) {
        unitRepository.findByCaseIdAndAlertId(caseId, alertId).ifPresent(unit -> {
            if (unit.getCurrentSubmissionId() != null) {
                submissionRepository.findById(unit.getCurrentSubmissionId()).ifPresent(submission -> {
                    submission.setState(SubmissionState.STALE);
                    submission.setSupersededReason(reason);
                    submissionRepository.save(submission);
                });
            }
            // 保留单元行以维持提交外键与回放；当前提交已置 STALE，新范围需重新提交。
            bumpEpoch(caseId, "UNIT_REMOVED:" + reason);
            auditOutbox.enqueue("EXPLANATION_UNIT_REMOVED:" + caseId + ":" + alertId,
                    operator, "EXPLANATION_UNIT_REMOVED", "CASE", String.valueOf(caseId),
                    "alertId=" + alertId + ",reason=" + reason);
        });
    }

    // ==================== 草稿 ====================

    /** 保存六问题草稿：expectedDraftRevision 乐观锁；草稿不产生最终业务判断、不推进 epoch。 */
    @Transactional
    public ExplanationViews.UnitView saveDraft(Long caseId, Long unitId, int expectedDraftRevision,
                                               String draftJson, String actor) {
        AlertExplanationUnit unit = requireUnit(caseId, unitId);
        if (unit.getCurrentSubmissionId() != null) {
            throw new IllegalStateException("该预警已有采用的提交；请先通过修订（amendment）撤回后再编辑草稿");
        }
        if (unit.getDraftRevision() != expectedDraftRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, unitId,
                    unit.getDraftRevision(),
                    "草稿版本已变化（当前 " + unit.getDraftRevision() + "，请求基于 " + expectedDraftRevision
                            + "），请刷新后重试");
        }
        validateDraftParses(draftJson);
        unit.setDraftJson(draftJson);
        unit.setDraftRevision(unit.getDraftRevision() + 1);
        unit.setEditors(appendActor(unit.getEditors(), actor));
        unitRepository.save(unit);
        return unitView(unit);
    }

    // ==================== 材料抓取与核验 ====================

    /** 抓取受控材料：只接受已配置来源系统与不透明引用；登记 ≠ 已核验；新材料进入待分派清单。 */
    @Transactional
    public ExplanationViews.EvidenceView captureEvidence(Long caseId, String sourceSystem,
                                                         String sourceReference, String contentSha256,
                                                         String claimedSha256, String actor) {
        requireCase(caseId);
        String system = upper(sourceSystem);
        if (!TRUSTED_SOURCE_SYSTEMS.contains(system)) {
            throw new IllegalArgumentException("来源系统不受信任：" + sourceSystem);
        }
        String reference = sourceReference == null ? "" : sourceReference.trim();
        if (!reference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}")) {
            throw new IllegalArgumentException("来源引用需为不透明记录编号（3 ~ 160 字符）");
        }
        String actualHash = requireSha256(contentSha256, "实际取得内容哈希");
        String artifactKey = (system + ":" + reference).toLowerCase(Locale.ROOT);
        var existing = artifactRepository.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(caseId, artifactKey);
        if (existing.isPresent()) {
            // 同一来源重复抓取：返回既有版本（不产生新“独立核验”），由调用方决定是否核验。
            return evidenceView(existing.get());
        }
        EvidenceArtifactVersion artifact = new EvidenceArtifactVersion();
        artifact.setCaseId(caseId);
        artifact.setArtifactKey(artifactKey);
        artifact.setVersion(1);
        artifact.setSourceSystem(system);
        artifact.setSourceReference(reference);
        artifact.setContentSha256(actualHash);
        artifact.setClaimedSha256(claimedSha256 == null || claimedSha256.isBlank()
                ? null : requireSha256(claimedSha256, "来源声称哈希"));
        artifact.setAvailability("RESOLVED");
        boolean mismatch = artifact.getClaimedSha256() != null
                && !artifact.getClaimedSha256().equals(actualHash);
        artifact.setIntegrityStatus(artifact.getClaimedSha256() == null ? "NOT_CHECKED"
                : (mismatch ? "MISMATCH" : "MATCH"));
        artifact.setSourceChain(system + "/" + reference + "@v1");
        artifact.setCapturedBy(actor);
        artifact.setCapturedAt(LocalDateTime.now(clock));
        EvidenceArtifactVersion saved = artifactRepository.save(artifact);
        if (mismatch) {
            // 内容与来源声称不一致：完整性/身份错误，阻断以该材料形成任何最终决定（V2-11）。
            ensureIssue(caseId, null, "INTEGRITY:" + artifactKey + ":v1", IssueSeverity.INTEGRITY_BLOCKER,
                    null, "材料 " + artifactKey + " 的实际内容哈希与来源声称不一致，不得作为已核实依据使用；"
                            + "需更正来源后重新抓取。", actor);
        }
        // 新材料尚未关联单元：进入案件待分派事实清单（V2-12）。
        ensureIssue(caseId, null, "FACT_UNASSIGNED:" + artifactKey + ":v1", IssueSeverity.DECISION_CRITICAL,
                null, "新材料 " + artifactKey + " 尚未关联任何预警单元；最终决定前需明确关联或说明不相关理由。",
                actor);
        bumpEpoch(caseId, "ARTIFACT_CAPTURED:" + artifactKey + ":v1");
        auditOutbox.enqueue("EXPLANATION_ARTIFACT:" + caseId + ":" + artifactKey + ":v1",
                actor, "EXPLANATION_ARTIFACT_CAPTURED", "CASE", String.valueOf(caseId),
                "artifactKey=" + artifactKey + ",integrity=" + saved.getIntegrityStatus());
        return evidenceView(saved);
    }

    /** 记录具体核验动作：技术解析与人工作用判断分开；核验变更推进依据版本。 */
    @Transactional
    public ExplanationViews.VerificationView recordVerification(Long caseId, Long artifactVersionId,
                                                                String method, String observedFacts,
                                                                String limitations, String result,
                                                                String actor) {
        EvidenceArtifactVersion artifact = artifactRepository.findByIdAndCaseId(artifactVersionId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("材料版本不存在：" + artifactVersionId));
        if (!"RESOLVED".equals(artifact.getAvailability())) {
            throw new IllegalStateException("来源不可用（" + artifact.getAvailability() + "），不能记录核验结果");
        }
        String normalizedResult = upper(result);
        if (!VERIFICATION_RESULTS.contains(normalizedResult)) {
            throw new IllegalArgumentException("核验结果必须是 CONFIRMED / MISMATCH / UNRESOLVED");
        }
        String normalizedMethod = upper(method);
        if (normalizedMethod.length() < 4 || normalizedMethod.length() > 64) {
            throw new IllegalArgumentException("核验方法需为 4 ~ 64 个字符");
        }
        String facts = observedFacts == null ? "" : observedFacts.trim();
        if (facts.length() < 10 || facts.length() > 2000) {
            throw new IllegalArgumentException("观察到的核验事实需为 10 ~ 2000 个字符");
        }
        Long previous = verificationRepository.findByArtifactVersionIdOrderByEventTimeAsc(artifactVersionId)
                .stream().reduce((first, second) -> second).map(EvidenceVerificationEvent::getId).orElse(null);
        EvidenceVerificationEvent event = new EvidenceVerificationEvent();
        event.setCaseId(caseId);
        event.setArtifactVersionId(artifactVersionId);
        event.setMethod(normalizedMethod);
        event.setObservedFacts(facts);
        event.setLimitations(limitations == null ? null : limitations.trim());
        event.setResult(normalizedResult);
        event.setActor(actor);
        event.setPreviousEventId(previous);
        event.setEventTime(LocalDateTime.now(clock));
        EvidenceVerificationEvent saved = verificationRepository.save(event);
        if ("MISMATCH".equals(normalizedResult)) {
            artifact.setIntegrityStatus("MISMATCH");
            artifactRepository.save(artifact);
            ensureIssue(caseId, null, "INTEGRITY:" + artifact.getArtifactKey() + ":v" + artifact.getVersion(),
                    IssueSeverity.INTEGRITY_BLOCKER, null,
                    "核验发现材料内容与独立来源不一致（方法 " + normalizedMethod + "），不得作为已核实依据。",
                    actor);
        }
        bumpEpoch(caseId, "ARTIFACT_VERIFIED:" + artifactVersionId + ":" + saved.getId());
        auditOutbox.enqueue("EXPLANATION_VERIFICATION:" + caseId + ":" + saved.getId(),
                actor, "EXPLANATION_ARTIFACT_VERIFIED", "CASE", String.valueOf(caseId),
                "artifactVersionId=" + artifactVersionId + ",method=" + normalizedMethod
                        + ",result=" + normalizedResult);
        return new ExplanationViews.VerificationView(saved.getId(), artifactVersionId, saved.getMethod(),
                saved.getObservedFacts(), saved.getLimitations(), saved.getResult(), saved.getActor(),
                String.valueOf(saved.getEventTime()));
    }

    /** 材料/来源变更：按反向引用把受影响单元的当前提交置 STALE，旧提交仍可回放（V2-11）。 */
    @Transactional
    public List<Long> markArtifactSuperseded(Long caseId, Long artifactVersionId, String reason,
                                             String actor) {
        EvidenceArtifactVersion artifact = artifactRepository.findByIdAndCaseId(artifactVersionId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("材料版本不存在：" + artifactVersionId));
        List<Long> affected = new ArrayList<>();
        for (ExplanationEvidenceUse use : evidenceUseRepository
                .findByCaseIdAndArtifactVersionId(caseId, artifactVersionId)) {
            ExplanationSubmission submission = submissionRepository.findById(use.getSubmissionId())
                    .orElse(null);
            if (submission == null || submission.getState() != SubmissionState.CURRENT) {
                continue;
            }
            submission.setState(SubmissionState.STALE);
            submission.setSupersededReason("ARTIFACT_SUPERSEDED:" + artifact.getArtifactKey()
                    + (reason == null ? "" : ":" + reason));
            submissionRepository.save(submission);
            affected.add(submission.getId());
            unitRepository.findById(submission.getUnitId()).ifPresent(unit -> {
                if (unitId(unit).equals(submission.getId())) {
                    unit.setCurrentSubmissionId(null);
                    unit.setDraftJson(submission.getPayloadJson());
                    unit.setDraftRevision(unit.getDraftRevision() + 1);
                    unitRepository.save(unit);
                }
                resetCoverageToPending(unit);
            });
        }
        bumpEpoch(caseId, "ARTIFACT_SUPERSEDED:" + artifact.getArtifactKey());
        auditOutbox.enqueue("EXPLANATION_STALE:" + caseId + ":" + artifactVersionId,
                actor, "EXPLANATION_SUBMISSION_STALE", "CASE", String.valueOf(caseId),
                "artifactVersionId=" + artifactVersionId + ",affectedSubmissions=" + affected);
        return affected;
    }

    // ==================== 提交 / 修订 ====================

    /**
     * 原子提交本单元建议与当前覆盖（v2 计划 §9/§10）。
     * 一次动作完成：草稿校验 → 幂等检查 → 令牌核对 → 政策适用性 → 六问题约束 →
     * 创建不可变提交 → 更新当前覆盖与单元指针 → 假设汇总 → epoch 推进 → 审计。
     */
    @Transactional
    public ExplanationViews.SubmissionResult submitUnit(Long caseId, Long unitId, int expectedDraftRevision,
                                                        String reviewBasisToken, String idempotencyKey,
                                                        String actor) {
        CaseEntity caseEntity = requireLockedExplanationCase(caseId);
        AlertExplanationUnit unit = requireUnit(caseId, unitId);

        // 幂等：同键同请求返回原提交 ID 与当前状态（可能是 STALE，V2-22）；同键不同请求 409。
        String normalizedKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (normalizedKey != null) {
            var replay = submissionRepository.findByIdempotencyKey(normalizedKey);
            if (replay.isPresent()) {
                ExplanationSubmission previous = replay.get();
                String replayDigest = inputDigestOf(caseEntity, unit);
                if (!previous.getInputDigest().equals(replayDigest)) {
                    throw new InvestigationRevisionConflictException(
                            InvestigationRevisionConflictException.TYPE_COVERAGE, unitId, null,
                            "相同幂等键对应不同提交内容，请更换幂等键后重试");
                }
                return new ExplanationViews.SubmissionResult(previous.getId(), previous.getUnitId(),
                        previous.getSubmissionNo(), previous.getOutcome(), previous.getState(),
                        currentAggregate(caseId), reviewBasisToken(caseId), List.of(
                        "幂等重放：返回原提交 " + previous.getId() + "（当前状态 " + previous.getState() + "）"));
            }
        }

        if (unit.getDraftRevision() != expectedDraftRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, unitId,
                    unit.getDraftRevision(),
                    "草稿版本已变化（当前 " + unit.getDraftRevision() + "，请求基于 " + expectedDraftRevision
                            + "），请刷新后重新提交");
        }
        // 令牌在案件锁内与当前事实比较（V2-15）：浏览器不得自行拼版本。
        String expectedToken = reviewBasisToken(caseId);
        if (reviewBasisToken == null || !reviewBasisToken.trim().equals(expectedToken)) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, unitId, null,
                    "提交依据令牌已失效（案件事实已变化或未通过 GET /review-basis 获取），请刷新后重新提交");
        }
        JsonNode draft = validateDraftParses(unit.getDraftJson());

        // 政策适用性：核定结果与声明必须一致；不适用保留线索并登记问题（§4）。
        ExplanationPolicyCatalog.Applicability applicability = readApplicability(draft);
        ExplanationPolicyCatalog.Resolution resolution = policyCatalog.resolve(applicability);
        if (!resolution.applicable()) {
            ensureIssue(caseId, unitId, "POLICY_NOT_APPLICABLE:" + unitId, IssueSeverity.DECISION_CRITICAL,
                    null, "配方适用性核定未通过：" + resolution.notApplicableReason(), actor);
            throw new IllegalArgumentException(ExplanationPolicyCatalog.POLICY_NOT_APPLICABLE
                    + "：" + resolution.notApplicableReason());
        }
        if (unit.getPolicyCode() != null && !unit.getPolicyCode().equals(resolution.policyCode())) {
            throw new IllegalArgumentException("配方声明与已核定配方不一致（已核定 "
                    + unit.getPolicyCode() + "，本次声明 " + resolution.policyCode() + "）；不能切换更宽松配方");
        }
        unit.setPolicyCode(resolution.policyCode());

        DraftSummary summary = validateDraftContent(caseEntity, unit, draft, applicability, resolution.policyCode());

        // 创建不可变提交
        int nextNo = submissionRepository.findTopByUnitIdOrderBySubmissionNoDesc(unitId)
                .map(ExplanationSubmission::getSubmissionNo).orElse(0) + 1;
        ExplanationSubmission submission = new ExplanationSubmission();
        submission.setUnitId(unitId);
        submission.setCaseId(caseId);
        submission.setSubmissionNo(nextNo);
        submission.setPayloadJson(unit.getDraftJson());
        submission.setOutcome(summary.outcome());
        submission.setSuspicionBasisComplete(summary.suspicionBasisComplete());
        submission.setCriticalUnknown(summary.criticalUnknown());
        submission.setUnresolvedDisclosed(summary.unresolvedDisclosed());
        submission.setFollowupRequired(summary.followupRequired());
        submission.setInputDigest(summary.inputDigest());
        submission.setIdempotencyKey(normalizedKey);
        submission.setState(SubmissionState.CURRENT);
        String contributors = deriveContributors(caseEntity, unit, summary.referencedArtifactIds(),
                summary.referencedIssueIds(), actor);
        submission.setContributors(contributors);
        submission.setSubmittedBy(actor);
        submission.setSubmittedAt(LocalDateTime.now(clock));
        ExplanationSubmission saved = submissionRepository.save(submission);

        // 当前指针 + 覆盖 + 假设汇总
        unit.setCurrentSubmissionId(saved.getId());
        unit.setDraftRevision(unit.getDraftRevision() + 1);
        unitRepository.save(unit);
        applyCoverageFromSubmission(unit, saved, actor);
        ExplanationOutcome aggregate = applyHypothesisAggregate(caseId, actor);

        bumpEpoch(caseId, "UNIT_SUBMITTED:" + unitId + ":" + saved.getId());
        auditOutbox.enqueue("EXPLANATION_SUBMIT:" + caseId + ":" + unitId + ":" + saved.getId(),
                actor, "EXPLANATION_UNIT_SUBMITTED", "CASE", String.valueOf(caseId),
                "submissionId=" + saved.getId() + ",outcome=" + summary.outcome()
                        + ",policy=" + resolution.policyCode()
                        + ",criticalUnknown=" + summary.criticalUnknown()
                        + ",contributors=" + contributors);
        return new ExplanationViews.SubmissionResult(saved.getId(), unitId, nextNo, summary.outcome(),
                SubmissionState.CURRENT, aggregate, reviewBasisToken(caseId), List.of());
    }

    /** 修订：撤回当前提交、以冻结 payload 开始下一草稿、推进 epoch；旧提交可回放但不可被最终采用（V2-13）。 */
    @Transactional
    public ExplanationViews.UnitView amendUnit(Long caseId, Long unitId, Long currentSubmissionId,
                                               String actor) {
        requireLockedExplanationCase(caseId);
        AlertExplanationUnit unit = requireUnit(caseId, unitId);
        if (unit.getCurrentSubmissionId() == null || !unit.getCurrentSubmissionId().equals(currentSubmissionId)) {
            throw new IllegalStateException("当前采用的提交已变化，请刷新后重试");
        }
        ExplanationSubmission submission = submissionRepository.findById(currentSubmissionId)
                .orElseThrow(() -> new IllegalArgumentException("提交不存在：" + currentSubmissionId));
        if (submission.getState() != SubmissionState.CURRENT) {
            throw new IllegalStateException("仅当前采用（CURRENT）的提交可以被修订");
        }
        submission.setState(SubmissionState.WITHDRAWN);
        submission.setSupersededReason("AMENDED_BY_" + actor);
        submissionRepository.save(submission);
        unit.setCurrentSubmissionId(null);
        unit.setDraftJson(submission.getPayloadJson());
        unit.setDraftRevision(unit.getDraftRevision() + 1);
        unit.setEditors(appendActor(unit.getEditors(), actor));
        unitRepository.save(unit);
        resetCoverageToPending(unit);
        applyHypothesisAggregate(caseId, actor);
        bumpEpoch(caseId, "UNIT_AMENDED:" + unitId + ":" + currentSubmissionId);
        auditOutbox.enqueue("EXPLANATION_AMEND:" + caseId + ":" + unitId + ":" + currentSubmissionId,
                actor, "EXPLANATION_UNIT_AMENDED", "CASE", String.valueOf(caseId),
                "withdrawnSubmissionId=" + currentSubmissionId);
        return unitView(unit);
    }

    // ==================== 问题处置 ====================

    /** 问题处置：解决 / 说明不相关 / 披露未解决；重要性降级需不同复核人确认（V2-19）。 */
    @Transactional
    public ExplanationViews.IssueView disposeIssue(Long caseId, Long issueId, int expectedRevision,
                                                   String disposition, String reason,
                                                   String evidenceReference, String downgradeTo,
                                                   String confirmedBy, String actor) {
        CaseEntity caseEntity = requireLockedExplanationCase(caseId);
        ExplanationIssue issue = issueRepository.findByIdAndCaseId(issueId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("问题不存在：" + issueId));
        if (issue.getRevision() != expectedRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, issueId,
                    issue.getRevision(),
                    "问题已被他人处置（当前版本 " + issue.getRevision() + "），请刷新后重试");
        }
        IssueDisposition normalized = parseEnum(IssueDisposition.class, disposition, "问题处置");
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalized == IssueDisposition.RESOLVED_WITH_EVIDENCE) {
            if (normalizedReason.length() < 10) {
                throw new IllegalArgumentException("解决问题需记录具体处置说明（至少 10 个字符）");
            }
            String reference = evidenceReference == null ? "" : evidenceReference.trim();
            if (!reference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}")) {
                throw new IllegalArgumentException("解决依据需提供可定位的证据引用");
            }
            issue.setResolvedBy(actor);
            issue.setResolvedAt(LocalDateTime.now(clock));
        } else if (normalized == IssueDisposition.NOT_RELEVANT_WITH_REASON) {
            if (issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER) {
                // 完整性/身份错误：不能以业务文字豁免，也不能降级（V2-19）。
                throw new IllegalArgumentException("完整性/身份错误不能被认定不相关；需更正来源/范围后重新评估");
            }
            if (normalizedReason.length() < 20) {
                throw new IllegalArgumentException("不相关认定需记录明确理由（至少 20 个字符）及其对本次决定的影响");
            }
            issue.setResolvedBy(actor);
            issue.setResolvedAt(LocalDateTime.now(clock));
        } else if (normalized == IssueDisposition.DISCLOSED_UNRESOLVED) {
            if (issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER) {
                throw new IllegalArgumentException("完整性/身份错误不能披露为未解决后继续采用；需先修复来源");
            }
            if (normalizedReason.length() < 10) {
                throw new IllegalArgumentException("披露未解决需说明其内容与对本次判断的影响");
            }
        } else {
            issue.setDisposition(IssueDisposition.OPEN);
            issue.setResolvedBy(null);
            issue.setResolvedAt(null);
            issueRepository.save(issue);
            bumpEpoch(caseId, "ISSUE_REOPENED:" + issueId);
            return issueView(issueRepository.findById(issueId).orElseThrow());
        }
        issue.setDisposition(normalized);
        issue.setDispositionReason(normalizedReason);
        if (downgradeTo != null && !downgradeTo.isBlank()) {
            IssueSeverity target = parseEnum(IssueSeverity.class, downgradeTo, "目标重要性");
            if (target == IssueSeverity.INTEGRITY_BLOCKER
                    || issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER) {
                throw new IllegalArgumentException("完整性/身份错误不允许降级");
            }
            if (rank(target) >= rank(issue.getSeverity())) {
                throw new IllegalArgumentException("只能向更低重要性降级");
            }
            String confirmer = confirmedBy == null ? "" : confirmedBy.trim();
            if (confirmer.isEmpty() || confirmer.equals(actor)) {
                throw new IllegalArgumentException("重要性降级需与处理人不同的复核人确认（V2-19）");
            }
            if (normalizedReason.length() < 20) {
                throw new IllegalArgumentException("降级需记录原等级、理由和引用（至少 20 个字符）");
            }
            issue.setSeverity(target);
            issue.setConfirmedBy(confirmer);
        }
        issue.setRevision(issue.getRevision() + 1);
        issue.setUpdatedAt(LocalDateTime.now(clock));
        ExplanationIssue saved = issueRepository.save(issue);
        // 关键问题处置影响被采用的依据：推进案件事实序号（§10.1）。
        bumpEpoch(caseId, "ISSUE_DISPOSED:" + issueId + ":" + saved.getDisposition());
        auditOutbox.enqueue("EXPLANATION_ISSUE:" + caseId + ":" + issueId + ":" + saved.getRevision(),
                actor, "EXPLANATION_ISSUE_DISPOSITION", "CASE", String.valueOf(caseId),
                "issueKey=" + saved.getIssueKey() + ",disposition=" + saved.getDisposition()
                        + ",severity=" + saved.getSeverity()
                        + (saved.getConfirmedBy() == null ? "" : ",confirmedBy=" + saved.getConfirmedBy()));
        return issueView(saved);
    }

    // ==================== 就绪与最终复核依据 ====================

    /** v2 案件的就绪结论（混合结论按决策表执行；供详情页与运营队列复用）。 */
    @Transactional(readOnly = true)
    public InvestigationReadinessResult readinessResult(Long caseId) {
        CaseEntity caseEntity = requireCase(caseId);
        return evaluateReadiness(caseEntity, null);
    }

    /** 最终复核前校验：决策表 + 令牌 + 实质贡献人自审限制。 */
    @Transactional(readOnly = true)
    public void validateReadyForReview(CaseEntity caseEntity, ReviewDecision decision,
                                       String reviewer, String reviewBasisToken) {
        if (decision == ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) {
            return;
        }
        // 义务接续已在同一事务内先行完成（ReviewService 先调用 transferObligations），
        // 因此按“接续已安排”口径评估任务门槛；OPEN DECISION_SUPPORT 且无计划时仍被阻断。
        InvestigationReadinessResult readiness = evaluateReadiness(caseEntity, reviewer, true);
        List<String> blockers = switch (decision) {
            case CONFIRM_SUSPICIOUS -> readiness.confirmBlockers();
            case EXCLUDE_FALSE_POSITIVE -> readiness.excludeBlockers();
            default -> List.of();
        };
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("调查尚未满足最终处置条件：" + String.join("；", blockers));
        }
        String expectedToken = reviewBasisToken(caseEntity.getId());
        if (reviewBasisToken == null || !reviewBasisToken.trim().equals(expectedToken)) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, caseEntity.getId(), null,
                    "最终复核依据令牌已失效（案件事实在取号后已变化），请重新获取复核依据");
        }
    }

    /** GET /review-basis：冻结候选依据 + 决策表 + 令牌（§13）。 */
    @Transactional(readOnly = true)
    public ExplanationViews.ReviewBasisView getReviewBasis(Long caseId, String reviewer) {
        CaseEntity caseEntity = requireCase(caseId);
        InvestigationReadinessResult readiness = evaluateReadiness(caseEntity, reviewer);
        List<ExplanationViews.UnitView> adopted = unitRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .filter(unit -> unit.getCurrentSubmissionId() != null)
                .map(this::unitView).toList();
        Map<String, String> outcomes = new java.util.LinkedHashMap<>();
        for (ExplanationViews.UnitView unit : adopted) {
            outcomes.put(String.valueOf(unit.unitId()), String.valueOf(unit.currentOutcome()));
        }
        List<ExplanationViews.IssueView> openIssues = issueRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .filter(issue -> issue.getDisposition() == IssueDisposition.OPEN)
                .map(this::issueView).toList();
        return new ExplanationViews.ReviewBasisView(caseId, caseEntity.getCaseFactsEpoch(),
                reviewBasisToken(caseId), readiness.reviewerIndependent(),
                readiness.canExclude(), readiness.canConfirm(),
                readiness.confirmBlockers(), readiness.excludeBlockers(), adopted, openIssues, outcomes);
    }

    // ==================== 内部：决策表映射 ====================

    /** v2 就绪评估结果（复用 InvestigationReadinessEvaluator.Result 形状，避免口径分叉）。 */
    public record InvestigationReadinessResult(
            boolean readyForFinalReview,
            List<String> generalBlockers,
            List<String> confirmBlockers,
            List<String> excludeBlockers,
            boolean canExclude,
            boolean canConfirm,
            boolean reviewerIndependent
    ) {
    }

    private InvestigationReadinessResult evaluateReadiness(CaseEntity caseEntity, String reviewer) {
        return evaluateReadiness(caseEntity, reviewer, false);
    }

    /** continuationArranged=最终复核事务内已完成义务接续（§8.2）；就绪预览阶段恒为 false。 */
    private InvestigationReadinessResult evaluateReadiness(CaseEntity caseEntity, String reviewer,
                                                          boolean continuationArranged) {
        int version = caseEntity.getInvestigationContractVersion();
        if (version >= 3) {
            return new InvestigationReadinessResult(false,
                    List.of("调查契约版本 " + version + " 不受支持，拒绝写入与最终处置"),
                    List.of("调查契约版本 " + version + " 不受支持"),
                    List.of("调查契约版本 " + version + " 不受支持"), false, false, true);
        }
        List<AlertExplanationUnit> units = unitRepository.findByCaseIdOrderByIdAsc(caseEntity.getId());
        List<ExplanationSubmission> current = submissionRepository
                .findByCaseIdAndStateOrderByIdAsc(caseEntity.getId(), SubmissionState.CURRENT);
        List<ExplanationIssue> issues = issueRepository.findByCaseIdOrderByIdAsc(caseEntity.getId());
        List<ExplanationViews.UnitView> unitViews = units.stream().map(this::unitView).toList();

        List<String> general = new ArrayList<>();
        boolean scopeEnumerated = issues.stream().noneMatch(issue ->
                issue.getDisposition() == IssueDisposition.OPEN
                        && issue.getIssueKey().startsWith(SCOPE_ISSUE_KEY));
        if (!scopeEnumerated) {
            general.add("存在未解决的预警范围枚举问题（ALERT_SCOPE_UNRESOLVED）：命中集合未证明完整");
        }
        boolean adoptedFactsUsable = issues.stream().noneMatch(issue ->
                issue.getDisposition() == IssueDisposition.OPEN
                        && issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER);
        if (!adoptedFactsUsable) {
            general.add("存在未解决的完整性/身份错误（INTEGRITY_BLOCKER）：不得以被破坏的依据形成最终决定");
        }
        boolean policyApplicable = !units.isEmpty() && units.stream()
                .allMatch(unit -> unit.getPolicyCode() != null);
        if (!policyApplicable) {
            general.add("存在尚未核定适用配方的预警单元");
        }
        // 未提交草稿永远不能满足门禁（§9）
        List<UnitAssessmentRow> rows = new ArrayList<>();
        for (AlertExplanationUnit unit : units) {
            ExplanationSubmission submission = unit.getCurrentSubmissionId() == null ? null
                    : submissionRepository.findById(unit.getCurrentSubmissionId()).orElse(null);
            if (submission == null || submission.getState() != SubmissionState.CURRENT) {
                general.add("预警 " + unit.getAlertId() + " 尚无可采用的单元提交（未提交草稿不能满足门禁）");
                continue;
            }
            boolean unitCriticalUnknown = submission.isCriticalUnknown()
                    || issues.stream().anyMatch(issue -> unit.getId().equals(issue.getUnitId())
                    && issue.getDisposition() == IssueDisposition.OPEN
                    && issue.getSeverity() == IssueSeverity.DECISION_CRITICAL);
            rows.add(new UnitAssessmentRow(unit, new ExplanationDecisionRules.UnitAssessment(
                    submission.getOutcome(), true, unitCriticalUnknown,
                    submission.isSuspicionBasisComplete(), submission.isUnresolvedDisclosed(),
                    submission.isFollowupRequired())));
        }
        boolean otherScenarioGate = !unitViews.isEmpty()
                && unitViews.size() == linkedAlertCount(caseEntity.getId())
                && rows.size() == unitViews.size();
        if (!otherScenarioGate) {
            general.add("并非所有有效预警都有已采用的单元提交（范围完整性未满足）");
        }
        boolean hasOpenDecisionSupportEdd = !eddRepository
                .findByCaseIdAndStatusOrderByIdAsc(caseEntity.getId(), EnhancedDueDiligenceStatus.OPEN)
                .stream().filter(task -> task.getPurpose() == EddTaskPurpose.DECISION_SUPPORT).toList().isEmpty();
        // 决策表（§7.2）：reviewBasisCurrent=true（就绪口径使用当前事实）；任务接续在最终复核事务内完成，
        // 就绪预览阶段 obligationTransfer/continuationPlan 视为未就绪，由最终复核入口携带计划后放宽。
        ExplanationDecisionRules.CaseContext context = new ExplanationDecisionRules.CaseContext(
                caseEntity.getStatus() == CaseStatus.HOLD, scopeEnumerated, adoptedFactsUsable,
                true, policyApplicable, true, otherScenarioGate,
                hasOpenDecisionSupportEdd, continuationArranged, continuationArranged);
        List<ExplanationDecisionRules.UnitAssessment> assessments = rows.stream()
                .map(UnitAssessmentRow::assessment).toList();
        ExplanationDecisionRules.Decision decision = ExplanationDecisionRules.evaluate(context, assessments);
        // 决策表评估需要“全部单元可评估”：存在未提交单元时 cannot pass —— 与设计一致（units.every(assessmentValid)）。
        boolean reviewerIndependent = reviewer == null || contributorsOf(current).stream()
                .noneMatch(contributor -> contributor.equals(reviewer));
        List<String> confirm = new ArrayList<>(general);
        List<String> exclude = new ArrayList<>(general);
        if (!decision.canConfirm()) {
            confirm.add("当前单元组合不支持送最终确认可疑（存在未决/未披露未知或缺少怀疑依据）");
        }
        if (!decision.canExclude()) {
            exclude.add("当前单元组合不支持最终排除（并非全部预警解释成立）");
        }
        if (!reviewerIndependent && reviewer != null) {
            confirm.add("最终复核人属于本次采用提交的实质贡献人（自审限制）");
            exclude.add("最终复核人属于本次采用提交的实质贡献人（自审限制）");
        }
        boolean ready = confirm.isEmpty() || exclude.isEmpty();
        // readyForFinalReview 语义与 v1 一致：两种结案路径至少一条可走。
        return new InvestigationReadinessResult(ready, dedupe(general), dedupe(confirm), dedupe(exclude),
                decision.canExclude(), decision.canConfirm(), reviewerIndependent);
    }

    private record UnitAssessmentRow(AlertExplanationUnit unit, ExplanationDecisionRules.UnitAssessment assessment) {
    }

    // ==================== 提交内容校验 ====================

    private record DraftSummary(
            ExplanationOutcome outcome,
            boolean suspicionBasisComplete,
            boolean criticalUnknown,
            boolean unresolvedDisclosed,
            boolean followupRequired,
            String inputDigest,
            List<Long> referencedArtifactIds,
            List<Long> referencedIssueIds
    ) {
    }

    private DraftSummary validateDraftContent(CaseEntity caseEntity, AlertExplanationUnit unit,
                                              JsonNode draft,
                                              ExplanationPolicyCatalog.Applicability applicability,
                                              String policyCode) {
        String outcomeText = text(draft, "outcome");
        ExplanationOutcome outcome = parseEnum(ExplanationOutcome.class, outcomeText, "单元建议");
        JsonNode scope = draft.get("scope");
        if (scope == null || !scope.isObject()) {
            throw new IllegalArgumentException("草稿缺少范围（scope）声明");
        }
        List<String> reviewedTransactions = stringList(scope, "reviewedTransactionIds");
        if (reviewedTransactions.isEmpty()) {
            throw new IllegalArgumentException("范围必须包含 reviewedTransactionIds（命中交易的逐笔对应）");
        }
        Set<String> uniqueReviewed = new LinkedHashSet<>(reviewedTransactions);
        if (uniqueReviewed.size() != reviewedTransactions.size()) {
            throw new IllegalArgumentException("reviewedTransactionIds 存在重复（同笔资金金额只计一次）");
        }
        String scopeNote = text(scope, "scopeEnumerationNote");
        if (scopeNote.length() < 10) {
            throw new IllegalArgumentException("范围枚举说明需至少 10 个字符（如何确定命中集合完整）");
        }
        // ALERT_SCOPE_UNRESOLVED 问题必须已通过显式处置解决（§3.2）
        issueRepository.findByCaseIdAndIssueKey(caseEntity.getId(), SCOPE_ISSUE_KEY + ":" + unit.getId())
            .ifPresent(issue -> {
                if (issue.getDisposition() == IssueDisposition.OPEN) {
                    throw new IllegalStateException("预警范围枚举问题尚未解决（ALERT_SCOPE_UNRESOLVED）；"
                            + "请先说明枚举方法并处置该问题");
                }
            });

        // 金额与对账：Decimal、两位小数、分配合计与交易金额差 0.00（§11.2）
        JsonNode amounts = scope.get("transactionAmounts");
        JsonNode allocations = scope.get("allocations");
        if (amounts == null || !amounts.isObject() || amounts.size() == 0) {
            throw new IllegalArgumentException("范围必须声明各命中交易的金额（transactionAmounts）");
        }
        Map<String, String> amountByTx = new java.util.LinkedHashMap<>();
        com.fasterxml.jackson.databind.node.ObjectNode amountsObject = (com.fasterxml.jackson.databind.node.ObjectNode) amounts;
        amountsObject.fieldNames().forEachRemaining(
                tx -> amountByTx.put(tx, amountsObject.get(tx).asText()));
        for (String tx : uniqueReviewed) {
            if (!amountByTx.containsKey(tx)) {
                throw new IllegalArgumentException("命中交易 " + tx + " 缺少金额声明");
            }
            requireAmount(amountByTx.get(tx), "交易 " + tx + " 金额");
        }
        Map<String, java.math.BigDecimal> allocated = new java.util.LinkedHashMap<>();
        if (allocations != null && allocations.isArray()) {
            for (JsonNode allocation : allocations) {
                String tx = text(allocation, "transactionId");
                if (!uniqueReviewed.contains(tx)) {
                    throw new IllegalArgumentException("分配引用了未在命中范围内的交易：" + tx);
                }
                java.math.BigDecimal amount = requireAmount(text(allocation, "amount"), "分配金额");
                allocated.merge(tx, amount, java.math.BigDecimal::add);
            }
        }
        for (String tx : uniqueReviewed) {
            java.math.BigDecimal declared = new java.math.BigDecimal(amountByTx.get(tx));
            java.math.BigDecimal sum = allocated.getOrDefault(tx, java.math.BigDecimal.ZERO);
            if (declared.subtract(sum).compareTo(java.math.BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("交易 " + tx + " 的订单/用途分配合计与交易金额不一致"
                        + "（声明 " + declared.toPlainString() + "，分配 " + sum.toPlainString()
                        + "）；差额必须为 0.00，费用/退款用独立调整项表达");
            }
        }

        // 六问题
        JsonNode questions = draft.get("questions");
        if (questions == null || !questions.isObject()) {
            throw new IllegalArgumentException("草稿缺少六问题（questions）");
        }
        boolean criticalUnknown = false;
        List<Long> artifactIds = new ArrayList<>();
        List<Long> issueIds = new ArrayList<>();
        for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
            JsonNode question = questions.get(code);
            if (question == null || !question.isObject()) {
                throw new IllegalArgumentException("缺少问题 " + code + " 的评估");
            }
            QuestionAssessment assessment = parseEnum(QuestionAssessment.class,
                    text(question, "assessment"), "问题 " + code + " 评估");
            String judgement = text(question, "judgement");
            if (assessment == QuestionAssessment.NOT_APPLICABLE) {
                if (judgement.length() < 4) {
                    throw new IllegalArgumentException("问题 " + code + " 不适用时需说明不适用理由");
                }
            } else {
                if (judgement.length() < 10) {
                    throw new IllegalArgumentException("问题 " + code + " 的具体判断需至少 10 个字符");
                }
                if (text(question, "factLocation").length() < 4) {
                    throw new IllegalArgumentException("问题 " + code + " 需提供事实定位（材料/记录位置）");
                }
            }
            FactKind factKind = parseEnum(FactKind.class,
                    text(question, "factKind", "DOCUMENT_ASSERTION"), "问题 " + code + " 事实类型");
            if (assessment == QuestionAssessment.UNKNOWN || factKind == FactKind.UNKNOWN) {
                criticalUnknown = true;
            }
            for (JsonNode artifactId : intList(question, "artifactVersionIds")) {
                long id = artifactId.asLong();
                EvidenceArtifactVersion artifact = artifactRepository.findByIdAndCaseId(id, caseEntity.getId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "问题 " + code + " 引用的材料不属于当前案件：" + id));
                if ("MISMATCH".equals(artifact.getIntegrityStatus())) {
                    throw new IllegalArgumentException("问题 " + code + " 引用了完整性不符（MISMATCH）的材料："
                            + artifact.getArtifactKey() + "；需先更正来源（INTEGRITY_BLOCKER）");
                }
                artifactIds.add(id);
            }
            for (JsonNode issueId : intList(question, "issueIds")) {
                long id = issueId.asLong();
                ExplanationIssue issue = issueRepository.findByIdAndCaseId(id, caseEntity.getId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "问题 " + code + " 引用的问题不属于当前案件：" + id));
                issueIds.add(id);
            }
        }
        // OPEN 的关键问题阻断 EXPLAINED（未评估默认关键，§6.1）
        boolean openCritical = issueRepository.findByCaseIdAndUnitIdOrderByIdAsc(caseEntity.getId(), unit.getId())
                .stream().anyMatch(issue -> issue.getDisposition() == IssueDisposition.OPEN
                        && (issue.getSeverity() == IssueSeverity.DECISION_CRITICAL
                        || issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER));
        boolean caseIntegrityBlocked = issueRepository.findByCaseIdOrderByIdAsc(caseEntity.getId()).stream()
                .anyMatch(issue -> issue.getDisposition() == IssueDisposition.OPEN
                        && issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER);
        if (caseIntegrityBlocked && outcome != ExplanationOutcome.UNRESOLVED) {
            throw new IllegalStateException("案件存在未解决的完整性/身份错误（INTEGRITY_BLOCKER）；"
                    + "只能提交 UNRESOLVED 记录现状，不得以被破坏的依据形成 EXPLAINED/SUSPICIOUS");
        }

        // 预付款时间规则（§5.2）：注入 Clock 评估，服务端最终提交即时重新评价。
        boolean followupRequired = false;
        if (ExplanationPolicyCatalog.GOODS_PREPAY_V1.equals(policyCode)) {
            LocalDate today = LocalDate.now(clock);
            if (policyCatalog.deliveryNotYetDue(applicability, today)) {
                followupRequired = true; // NOT_YET_DUE：缺交付材料不是当前缺陷，但需持续跟进责任
            } else {
                // 交付日已过仍无交付或合理延期依据：产生需要人处理的当前关键问题（V2-03）。
                ensureIssue(caseEntity.getId(), unit.getId(),
                        "DELIVERY_OVERDUE:" + unit.getId(), IssueSeverity.DECISION_CRITICAL, "Q3",
                        "预付款约定交期已过且未提供交付或合理延期依据；不能继续按 NOT_YET_DUE 评价。",
                        "system");
                criticalUnknown = true;
            }
        }

        // 反证与怀疑依据
        JsonNode suspicionBasis = draft.get("suspicionBasis");
        boolean suspicionBasisComplete = false;
        if (outcome == ExplanationOutcome.SUSPICIOUS) {
            if (suspicionBasis == null || !suspicionBasis.isObject()) {
                throw new IllegalArgumentException("提交 SUSPICIOUS 需记录支持合理怀疑的依据（suspicionBasis）");
            }
            String assessedFacts = text(suspicionBasis, "assessedFacts");
            String reverseExplanations = text(suspicionBasis, "reverseExplanations");
            String whyInsufficient = text(suspicionBasis, "whyInsufficient");
            if (assessedFacts.length() < 10 || reverseExplanations.length() < 10
                    || whyInsufficient.length() < 10) {
                throw new IllegalArgumentException("怀疑依据需包含已评估事实、反向解释及为何仍不足以消除疑点"
                        + "（各至少 10 个字符）；解释“不满足”本身不够");
            }
            suspicionBasisComplete = true;
        }
        // EXPLAINED 约束：全部问题已回答（SATISFIED/NOT_APPLICABLE）且无关键未知
        if (outcome == ExplanationOutcome.EXPLAINED) {
            if (criticalUnknown || openCritical) {
                throw new IllegalArgumentException("存在关键未知或未解决的关键问题，不能建议 EXPLAINED；"
                        + "可选择 SUSPICIOUS（记录怀疑依据）或 UNRESOLVED（保留未知）");
            }
        }
        List<String> disclosed = stringList(draft, "disclosedUnknowns");
        boolean unresolvedDisclosed = !disclosed.isEmpty();
        if (outcome == ExplanationOutcome.UNRESOLVED && (criticalUnknown || openCritical)
                && !unresolvedDisclosed) {
            throw new IllegalArgumentException("提交 UNRESOLVED 且存在关键未知时，需在 disclosedUnknowns 中显式披露");
        }
        // 已披露未知不能被改写为 EXPLAINED（§7.3）
        if (outcome == ExplanationOutcome.EXPLAINED && unresolvedDisclosed) {
            throw new IllegalArgumentException("已披露未解决事项不能随 EXPLAINED 提交；"
                    + "它们只能由确认可疑路径以 DISCLOSED_UNRESOLVED 处置记录采用");
        }

        return new DraftSummary(outcome, suspicionBasisComplete, criticalUnknown || openCritical,
                unresolvedDisclosed, followupRequired, inputDigestOf(caseEntity, unit),
                artifactIds, issueIds);
    }

    // ==================== 覆盖与假设汇总 ====================

    private void applyCoverageFromSubmission(AlertExplanationUnit unit, ExplanationSubmission submission,
                                             String actor) {
        AlertCoverageConclusion conclusion = switch (submission.getOutcome()) {
            case EXPLAINED -> AlertCoverageConclusion.EXPLAINED;
            case SUSPICIOUS -> AlertCoverageConclusion.SUSPICIOUS;
            case UNRESOLVED -> AlertCoverageConclusion.PENDING; // 未解决事项在已有覆盖中保持 PENDING（§7.3）
        };
        AlertInvestigationCoverage coverage = coverageRepository.findByAlertId(unit.getAlertId())
                .orElseGet(() -> {
                    AlertInvestigationCoverage created = new AlertInvestigationCoverage();
                    created.setAlertId(unit.getAlertId());
                    created.setCaseId(unit.getCaseId());
                    created.setHypothesisId(unit.getHypothesisId());
                    created.setConclusion(AlertCoverageConclusion.PENDING);
                    return created;
                });
        coverage.setHypothesisId(unit.getHypothesisId());
        hypothesisRepository.findById(unit.getHypothesisId() == null ? -1L : unit.getHypothesisId())
                .ifPresent(hypothesis -> coverage.setHypothesisRevision((long) hypothesis.getRevision()));
        coverage.setConclusion(conclusion);
        coverage.setUnitSubmissionId(submission.getId());
        coverage.setAnalysisSummary(truncate("[" + submission.getOutcome() + "] 单元提交 #"
                + submission.getId() + "（" + submission.getSubmissionNo() + "）"));
        coverage.setUpdatedBy(actor);
        coverage.setRevision(coverage.getRevision() + 1);
        coverageRepository.save(coverage);
    }

    private void resetCoverageToPending(AlertExplanationUnit unit) {
        coverageRepository.findByAlertId(unit.getAlertId()).ifPresent(coverage -> {
            if (coverage.getUnitSubmissionId() != null) {
                coverage.setConclusion(AlertCoverageConclusion.PENDING);
                coverage.setUnitSubmissionId(null);
                coverage.setRevision(coverage.getRevision() + 1);
                coverageRepository.save(coverage);
            }
        });
    }

    /** 假设汇总规则（§3.3）：任一 SUSPICIOUS → CONFIRMED；全部 EXPLAINED → REJECTED；否则 OPEN。 */
    private ExplanationOutcome applyHypothesisAggregate(Long caseId, String actor) {
        List<ExplanationSubmission> current = submissionRepository
                .findByCaseIdAndStateOrderByIdAsc(caseId, SubmissionState.CURRENT);
        ExplanationOutcome aggregate;
        if (current.isEmpty()) {
            aggregate = ExplanationOutcome.UNRESOLVED;
        } else if (current.stream().anyMatch(item -> item.getOutcome() == ExplanationOutcome.SUSPICIOUS)) {
            aggregate = ExplanationOutcome.SUSPICIOUS;
        } else if (current.stream().allMatch(item -> item.getOutcome() == ExplanationOutcome.EXPLAINED)) {
            aggregate = ExplanationOutcome.EXPLAINED;
        } else {
            aggregate = ExplanationOutcome.UNRESOLVED;
        }
        HypothesisStatus status = switch (aggregate) {
            case SUSPICIOUS -> HypothesisStatus.CONFIRMED;
            case EXPLAINED -> HypothesisStatus.REJECTED;
            case UNRESOLVED -> HypothesisStatus.OPEN;
        };
        for (InvestigationHypothesis hypothesis : hypothesisRepository.findByCaseIdOrderByIdAsc(caseId)) {
            if (hypothesis.getStatus() != status) {
                // v2 单元汇总直接推进假设状态：依据在不可变提交内，不走 v1 证据类型门槛（§7.3）。
                hypothesis.setStatus(status);
                hypothesis.setRationale(truncate("解释核验单元汇总（v2）："
                        + (status == HypothesisStatus.CONFIRMED ? "存在支持怀疑的已评估发现"
                        : status == HypothesisStatus.REJECTED ? "全部预警解释成立"
                        : "存在未决单元")));
                hypothesis.setUpdatedBy(actor);
                hypothesis.setRevision(hypothesis.getRevision() + 1);
                hypothesisRepository.save(hypothesis);
            }
        }
        return aggregate;
    }

    // ==================== 令牌 ====================

    /** 依据令牌：绑定 caseId、caseFactsEpoch、当前采用提交、任务义务与政策摘要（§10.1）。 */
    public String reviewBasisToken(Long caseId) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        List<ExplanationSubmission> current = submissionRepository
                .findByCaseIdAndStateOrderByIdAsc(caseId, SubmissionState.CURRENT);
        List<EnhancedDueDiligenceRequest> openTasks = eddRepository
                .findByCaseIdAndStatusOrderByIdAsc(caseId, EnhancedDueDiligenceStatus.OPEN);
        StringBuilder payload = new StringBuilder();
        payload.append(caseId).append('|').append(caseEntity.getCaseFactsEpoch()).append('|');
        payload.append(current.stream().map(item -> item.getId() + ":" + item.getState() + ":"
                + item.getInputDigest().substring(0, 8)).sorted()
                .reduce((a, b) -> a + "," + b).orElse("-")).append('|');
        payload.append(openTasks.stream().map(item -> String.valueOf(item.getId())).sorted()
                .reduce((a, b) -> a + "," + b).orElse("-")).append('|');
        payload.append(unitRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(unit -> String.valueOf(unit.getPolicyCode())).sorted()
                .reduce((a, b) -> a + "," + b).orElse("-"));
        return sha256Hex(payload.toString());
    }

    // ==================== 贡献人 ====================

    /** 服务端派生实质贡献人：草稿编辑 + 引用材料的抓取/核验人 + 引用问题的处置人 + 提交人。 */
    private String deriveContributors(CaseEntity caseEntity, AlertExplanationUnit unit,
                                      List<Long> artifactIds, List<Long> issueIds, String actor) {
        Set<String> contributors = new LinkedHashSet<>();
        contributors.add(actor);
        if (unit.getEditors() != null) {
            for (String editor : unit.getEditors().split(",")) {
                if (!editor.isBlank()) {
                    contributors.add(editor.trim());
                }
            }
        }
        for (Long artifactId : artifactIds) {
            artifactRepository.findById(artifactId).ifPresent(artifact -> {
                contributors.add(artifact.getCapturedBy());
                for (EvidenceVerificationEvent event : verificationRepository
                        .findByArtifactVersionIdOrderByEventTimeAsc(artifact.getId())) {
                    contributors.add(event.getActor());
                }
            });
        }
        for (Long issueId : issueIds) {
            issueRepository.findById(issueId).ifPresent(issue -> {
                if (issue.getResolvedBy() != null) {
                    contributors.add(issue.getResolvedBy());
                }
            });
        }
        return String.join(",", contributors);
    }

    private Set<String> contributorsOf(List<ExplanationSubmission> submissions) {
        Set<String> contributors = new LinkedHashSet<>();
        for (ExplanationSubmission submission : submissions) {
            if (submission.getContributors() != null) {
                for (String contributor : submission.getContributors().split(",")) {
                    if (!contributor.isBlank()) {
                        contributors.add(contributor.trim());
                    }
                }
            }
        }
        return contributors;
    }

    // ==================== 视图与辅助 ====================

    private ExplanationViews.UnitView unitView(AlertExplanationUnit unit) {
        ExplanationSubmission current = unit.getCurrentSubmissionId() == null ? null
                : submissionRepository.findById(unit.getCurrentSubmissionId()).orElse(null);
        List<String> blockers = new ArrayList<>();
        if (current == null) {
            blockers.add("尚无可采用的提交");
        }
        issueRepository.findByCaseIdAndUnitIdOrderByIdAsc(unit.getCaseId(), unit.getId()).stream()
                .filter(issue -> issue.getDisposition() == IssueDisposition.OPEN)
                .forEach(issue -> blockers.add(issue.getSeverity() + ":" + issue.getDescription()));
        return new ExplanationViews.UnitView(unit.getId(), unit.getAlertId(), alertLabel(alertOf(unit)),
                unit.getHypothesisId(), unit.getPolicyCode(), unit.getDraftRevision(),
                current != null, unit.getCurrentSubmissionId(),
                current == null ? null : current.getOutcome(),
                current != null && current.isCriticalUnknown(),
                current != null && current.isFollowupRequired(), blockers);
    }

    private AmlAlert alertOf(AlertExplanationUnit unit) {
        return alertRepository.findById(unit.getAlertId()).orElse(null);
    }

    private String alertLabel(AmlAlert alert) {
        if (alert == null) {
            return "-";
        }
        return alert.getExternalAlertId() != null ? alert.getExternalAlertId()
                : String.valueOf(alert.getId());
    }

    private ExplanationViews.IssueView issueView(ExplanationIssue issue) {
        return new ExplanationViews.IssueView(issue.getId(), issue.getUnitId(), issue.getIssueKey(),
                issue.getSeverity(), issue.getQuestionCode(), issue.getTransactionIds(),
                issue.getDescription(), issue.getDisposition(), issue.getDispositionReason(),
                issue.getResolvedBy(), issue.getConfirmedBy(), issue.getRevision());
    }

    private ExplanationViews.EvidenceView evidenceView(EvidenceArtifactVersion artifact) {
        return new ExplanationViews.EvidenceView(artifact.getId(), artifact.getArtifactKey(),
                artifact.getVersion(), artifact.getSourceSystem(), artifact.getSourceReference(),
                artifact.getContentSha256(), artifact.getClaimedSha256(), artifact.getAvailability(),
                artifact.getIntegrityStatus(), artifact.getCapturedBy(), String.valueOf(artifact.getCapturedAt()));
    }

    private void ensureIssue(Long caseId, Long unitId, String issueKey, IssueSeverity severity,
                             String questionCode, String description, String createdBy) {
        if (issueRepository.findByCaseIdAndIssueKey(caseId, issueKey).isPresent()) {
            return;
        }
        ExplanationIssue issue = new ExplanationIssue();
        issue.setCaseId(caseId);
        issue.setUnitId(unitId);
        issue.setIssueKey(issueKey);
        issue.setSeverity(severity);
        issue.setQuestionCode(questionCode);
        issue.setDescription(description);
        issue.setDisposition(IssueDisposition.OPEN);
        issue.setRevision(0);
        issue.setCreatedBy(createdBy == null ? "system" : createdBy);
        issueRepository.save(issue);
    }

    private void bumpEpoch(Long caseId, String reason) {
        caseRepository.bumpFactsEpoch(caseId);
        auditOutbox.enqueue("EXPLANATION_EPOCH:" + caseId + ":" + reason,
                "system", "EXPLANATION_EPOCH_BUMP", "CASE", String.valueOf(caseId), "reason=" + reason);
    }

    private CaseEntity requireCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private CaseEntity requireLockedExplanationCase(Long caseId) {
        CaseEntity caseEntity = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        if (caseEntity.getInvestigationContractVersion() < 2) {
            throw new IllegalArgumentException("该案件未启用合理解释核验政策（v2）");
        }
        if (caseEntity.getInvestigationContractVersion() >= 3) {
            throw new IllegalArgumentException("调查契约版本 "
                    + caseEntity.getInvestigationContractVersion() + " 不受支持，拒绝写入");
        }
        return caseEntity;
    }

    private AlertExplanationUnit requireUnit(Long caseId, Long unitId) {
        return unitRepository.findByIdAndCaseId(unitId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("预警核验单元不存在：" + unitId));
    }

    private Long unitId(AlertExplanationUnit unit) {
        return unit.getCurrentSubmissionId();
    }

    private JsonNode validateDraftParses(String draftJson) {
        try {
            JsonNode draft = objectMapper.readTree(draftJson == null ? "" : draftJson);
            if (draft == null || !draft.isObject()) {
                throw new IllegalArgumentException("草稿必须是 JSON 对象");
            }
            return draft;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("草稿 JSON 解析失败：" + e.getMessage());
        }
    }

    private ExplanationPolicyCatalog.Applicability readApplicability(JsonNode draft) {
        JsonNode policy = draft.get("policy");
        if (policy == null || !policy.isObject()) {
            return null;
        }
        return new ExplanationPolicyCatalog.Applicability(
                text(policy, "businessRole"),
                text(policy, "paymentStage"),
                bool(policy, "payerMatchesContractBuyer"),
                bool(policy, "payeeMatchesContractSeller"),
                text(policy, "contractNumber"),
                text(policy, "deliveryDueDate"));
    }

    private String inputDigestOf(CaseEntity caseEntity, AlertExplanationUnit unit) {
        StringBuilder payload = new StringBuilder();
        payload.append(unit.getCaseId()).append('|').append(unit.getAlertId()).append('|')
                .append(unit.getPolicyCode()).append('|');
        if (unit.getDraftJson() != null) {
            // 固定排序：对草稿键排序后的规范化 JSON 摘要，顺序不敏感（§10.1）
            try {
                JsonNode normalized = objectMapper.readTree(unit.getDraftJson());
                payload.append(objectMapper.writeValueAsString(normalized));
            } catch (Exception e) {
                payload.append(unit.getDraftJson());
            }
        }
        payload.append("|epochAtDraft=").append(caseEntity.getCaseFactsEpoch());
        return sha256Hex(payload.toString());
    }

    private long linkedAlertCount(Long caseId) {
        return alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(alert -> alert.getStatus() == com.bank.aml.investigation.AlertStatus.LINKED)
                .count();
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static String appendActor(String editors, String actor) {
        String normalized = actor == null ? "" : actor.trim();
        if (editors == null || editors.isBlank()) {
            return normalized;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (String editor : editors.split(",")) {
            if (!editor.isBlank()) {
                existing.add(editor.trim());
            }
        }
        existing.add(normalized);
        return String.join(",", existing);
    }

    private static java.math.BigDecimal requireAmount(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !normalized.matches("\\d+(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException(field + " 需为定点数字符串（CNY 两位小数，如 \"320000.00\"）");
        }
        java.math.BigDecimal amount = new java.math.BigDecimal(normalized);
        if (amount.scale() > 2) {
            throw new IllegalArgumentException(field + " 最多两位小数");
        }
        return amount;
    }

    private static String requireSha256(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " 必须为 SHA-256 十六进制");
        }
        return normalized;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asText(defaultValue).trim();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode item : value) {
                if (!item.isNull()) {
                    result.add(item.asText().trim());
                }
            }
        }
        return result;
    }

    private static List<JsonNode> intList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<JsonNode> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(result::add);
        }
        return result;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + "不在允许范围内：" + value);
        }
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    /** 只读聚合：任一 SUSPICIOUS → SUSPICIOUS；全部 EXPLAINED → EXPLAINED；否则 UNRESOLVED（§3.3）。 */
    private ExplanationOutcome currentAggregate(Long caseId) {
        List<ExplanationSubmission> current = submissionRepository
                .findByCaseIdAndStateOrderByIdAsc(caseId, SubmissionState.CURRENT);
        if (current.isEmpty()) {
            return ExplanationOutcome.UNRESOLVED;
        }
        if (current.stream().anyMatch(item -> item.getOutcome() == ExplanationOutcome.SUSPICIOUS)) {
            return ExplanationOutcome.SUSPICIOUS;
        }
        if (current.stream().allMatch(item -> item.getOutcome() == ExplanationOutcome.EXPLAINED)) {
            return ExplanationOutcome.EXPLAINED;
        }
        return ExplanationOutcome.UNRESOLVED;
    }

    /** 重要性序（用于降级方向校验）。 */
    private static int rank(IssueSeverity severity) {
        return switch (severity) {
            case INTEGRITY_BLOCKER -> 3;
            case DECISION_CRITICAL -> 2;
            case CONTEXT_GAP -> 1;
            case FUTURE_OBLIGATION -> 0;
        };
    }

    /** {@link ExplanationReadinessPort}：映射为与 v1 相同的 Result 形状，避免口径分叉。 */
    @Override
    public com.bank.aml.investigation.InvestigationReadinessEvaluator.Result readinessForCase(Long caseId) {
        InvestigationReadinessResult result = readinessResult(caseId);
        return new com.bank.aml.investigation.InvestigationReadinessEvaluator.Result(
                result.readyForFinalReview(), result.generalBlockers(),
                result.confirmBlockers(), result.excludeBlockers());
    }
}
