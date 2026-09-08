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
    private final ExplanationIssueReviewRepository issueReviewRepository;
    private final com.bank.aml.security.UserAccountRepository userAccountRepository;
    private final AlertInvestigationCoverageRepository coverageRepository;
    private final InvestigationHypothesisRepository hypothesisRepository;
    private final com.bank.aml.investigation.AmlAlertRepository alertRepository;
    private final EnhancedDueDiligenceRequestRepository eddRepository;
    private final ExplanationPolicyCatalog policyCatalog;
    private final EvidenceAdmissibilityService admissibilityService;
    private final AuditOutboxService auditOutbox;
    private final EvidenceSourcePort evidenceSourcePort;
    private final com.bank.aml.datasource.CustomerDataPort customerDataPort;
    private final com.bank.aml.investigation.AlertScopeService alertScopeService;
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
                                       ExplanationIssueReviewRepository issueReviewRepository,
                                       com.bank.aml.security.UserAccountRepository userAccountRepository,
                                       AlertInvestigationCoverageRepository coverageRepository,
                                       InvestigationHypothesisRepository hypothesisRepository,
                                       com.bank.aml.investigation.AmlAlertRepository alertRepository,
                                       EnhancedDueDiligenceRequestRepository eddRepository,
                                       ExplanationPolicyCatalog policyCatalog,
                                       AuditOutboxService auditOutbox,
                                       EvidenceSourcePort evidenceSourcePort,
                                       com.bank.aml.datasource.CustomerDataPort customerDataPort,
                                       com.bank.aml.investigation.AlertScopeService alertScopeService,
                                       EvidenceAdmissibilityService admissibilityService,
                                       ObjectMapper objectMapper) {
        this(caseRepository, unitRepository, submissionRepository, issueRepository, basisRepository,
                artifactRepository, verificationRepository, evidenceUseRepository, issueReviewRepository,
                userAccountRepository, coverageRepository,
                hypothesisRepository, alertRepository, eddRepository, policyCatalog, auditOutbox,
                evidenceSourcePort, customerDataPort, alertScopeService, admissibilityService, objectMapper,
                Clock.systemDefaultZone());
    }

    ExplanationWorkspaceService(CaseRepository caseRepository,
                                AlertExplanationUnitRepository unitRepository,
                                ExplanationSubmissionRepository submissionRepository,
                                ExplanationIssueRepository issueRepository,
                                VerificationBasisRepository basisRepository,
                                EvidenceArtifactVersionRepository artifactRepository,
                                EvidenceVerificationEventRepository verificationRepository,
                                ExplanationEvidenceUseRepository evidenceUseRepository,
                                ExplanationIssueReviewRepository issueReviewRepository,
                                com.bank.aml.security.UserAccountRepository userAccountRepository,
                                AlertInvestigationCoverageRepository coverageRepository,
                                InvestigationHypothesisRepository hypothesisRepository,
                                com.bank.aml.investigation.AmlAlertRepository alertRepository,
                                EnhancedDueDiligenceRequestRepository eddRepository,
                                ExplanationPolicyCatalog policyCatalog,
                                AuditOutboxService auditOutbox,
                                EvidenceSourcePort evidenceSourcePort,
                                com.bank.aml.datasource.CustomerDataPort customerDataPort,
                                com.bank.aml.investigation.AlertScopeService alertScopeService,
                                EvidenceAdmissibilityService admissibilityService,
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
        this.issueReviewRepository = issueReviewRepository;
        this.userAccountRepository = userAccountRepository;
        this.coverageRepository = coverageRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.alertRepository = alertRepository;
        this.eddRepository = eddRepository;
        this.policyCatalog = policyCatalog;
        this.auditOutbox = auditOutbox;
        this.evidenceSourcePort = evidenceSourcePort;
        this.customerDataPort = customerDataPort;
        this.alertScopeService = alertScopeService;
        this.admissibilityService = admissibilityService;
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

    /**
     * 抓取受控材料（A5-01 修复）：来源内容与摘要由服务端 {@link EvidenceSourcePort} 真实取得；
     * 调用方提交的 contentSha256 不再被接受为权威输入。来源不存在 → NOT_FOUND 记录，
     * 来源暂不可用 → UNAVAILABLE 记录；二者都不能作为已核实依据形成结论。
     * 手工声明（CUSTOMER_PROVIDED 等未接适配器的系统）保持"声明/待核验"，按 NOT_CHECKED 处理。
     */
    @Transactional
    public ExplanationViews.EvidenceView captureEvidence(Long caseId, String sourceSystem,
                                                         String sourceReference, String actor) {
        requireCase(caseId);
        String system = upper(sourceSystem);
        if (!TRUSTED_SOURCE_SYSTEMS.contains(system)) {
            throw new IllegalArgumentException("来源系统不受信任：" + sourceSystem);
        }
        String reference = sourceReference == null ? "" : sourceReference.trim();
        if (!reference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}")) {
            throw new IllegalArgumentException("来源引用需为不透明记录编号（3 ~ 160 字符）");
        }
        String artifactKey = (system + ":" + reference).toLowerCase(Locale.ROOT);
        var existingOpt = artifactRepository.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(caseId, artifactKey);
        if (existingOpt.isPresent()) {
            // 同源再次抓取（A5-01/TP-17）：同内容幂等返回既有版本；不同内容 → 追加新版本，
            // 旧内容可回放；来源不可用/不存在 → 记录新版本状态，不覆盖旧版本。
            EvidenceArtifactVersion existing = existingOpt.get();
            var fetched = evidenceSourcePort.fetch(system, reference);
            if (fetched.isPresent() && fetched.get().availability() == EvidenceSourcePort.Availability.RESOLVED) {
                String newHash = fetched.get().contentSha256();
                if (newHash.equals(existing.getContentSha256())) {
                    return evidenceView(existing);
                }
                EvidenceArtifactVersion next = new EvidenceArtifactVersion();
                next.setCaseId(caseId);
                next.setArtifactKey(artifactKey);
                next.setVersion(existing.getVersion() + 1);
                next.setSourceSystem(system);
                next.setSourceReference(reference);
                next.setContentSha256(newHash);
                next.setClaimedSha256(null);
                next.setAvailability("RESOLVED");
                next.setIntegrityStatus("NOT_CHECKED");
                next.setSourceChain(system + "/" + reference + "@v" + next.getVersion());
                next.setCapturedBy(actor);
                next.setCapturedAt(LocalDateTime.now(clock));
                EvidenceArtifactVersion savedNext = artifactRepository.save(next);
                // TP-16/A5-05 语义：来源内容变更（新版本）→ 依赖旧版本的采用提交必须 STALE；
                // 旧版本仍可回放，新版本待重新核验后由新提交引用。
                List<Long> affected = staleSubmissionsUsingArtifact(caseId, existing.getId(),
                        "ARTIFACT_CONTENT_CHANGED:" + artifactKey + ":v" + next.getVersion(), actor);
                bumpEpoch(caseId, "ARTIFACT_CONTENT_CHANGED:" + artifactKey + ":v" + next.getVersion());
                auditOutbox.enqueue("EXPLANATION_ARTIFACT:" + caseId + ":" + artifactKey + ":v"
                                + next.getVersion(),
                        actor, "EXPLANATION_ARTIFACT_CONTENT_CHANGED", "CASE", String.valueOf(caseId),
                        "artifactKey=" + artifactKey + ",previousHash=" + existing.getContentSha256()
                                + ",affectedSubmissions=" + affected);
                return evidenceView(savedNext);
            }
            // 来源不可用/不存在：保持旧版本可回放，只记录最新状态问题（幂等返回旧版本 + 刷新问题）。
            if (fetched.isPresent()
                    && fetched.get().availability() != EvidenceSourcePort.Availability.RESOLVED) {
                ensureIssue(caseId, null, "SOURCE_UNAVAILABLE:" + artifactKey + ":v" + existing.getVersion(),
                        IssueSeverity.DECISION_CRITICAL, null,
                        "材料 " + artifactKey + " 最新抓取未取得内容（状态 "
                                + fetched.get().availability() + "）；旧版本仍可回放，"
                                + "以旧版本形成的采用结论需重新核验来源状态。",
                        actor);
            }
            return evidenceView(existing);
        }
        EvidenceArtifactVersion artifact = new EvidenceArtifactVersion();
        artifact.setCaseId(caseId);
        artifact.setArtifactKey(artifactKey);
        artifact.setVersion(1);
        artifact.setSourceSystem(system);
        artifact.setSourceReference(reference);
        artifact.setSourceChain(system + "/" + reference + "@v1");
        artifact.setCapturedBy(actor);
        artifact.setCapturedAt(LocalDateTime.now(clock));
        var fetched = evidenceSourcePort.fetch(system, reference);
        if (fetched.isPresent()) {
            EvidenceSourcePort.FetchedContent content = fetched.get();
            if (content.availability() != EvidenceSourcePort.Availability.RESOLVED) {
                // NOT_FOUND / UNAVAILABLE / FORBIDDEN：记录状态但不能作为已核实依据（A5-01）。
                artifact.setContentSha256(null);
                artifact.setClaimedSha256(null);
                artifact.setAvailability(content.availability().name());
                artifact.setIntegrityStatus("NOT_CHECKED");
            } else {
                artifact.setContentSha256(content.contentSha256());
                artifact.setClaimedSha256(null);
                artifact.setAvailability("RESOLVED");
                artifact.setIntegrityStatus("NOT_CHECKED");
            }
        } else {
            // 来源系统未接适配器（如 CUSTOMER_PROVIDED 手工登记）：声明/待核验，不是 RESOLVED。
            artifact.setContentSha256(null);
            artifact.setClaimedSha256(null);
            artifact.setAvailability("NOT_FOUND");
            artifact.setIntegrityStatus("NOT_CHECKED");
        }
        EvidenceArtifactVersion saved = artifactRepository.save(artifact);
        // 持久化不变式（A6-06/RC-02）：RESOLVED 必须有服务器计算的摘要；未取得内容必须无摘要。
        // 不用伪造摘要凑非空约束；冲突立即暴露而不是等真实 MySQL 才失败。
        if ("RESOLVED".equals(saved.getAvailability())) {
            if (saved.getContentSha256() == null
                    || !saved.getContentSha256().matches("[a-f0-9]{64}")) {
                throw new IllegalStateException("RESOLVED 材料缺少服务器计算的摘要（持久化不变式冲突）："
                        + artifactKey);
            }
        } else if (saved.getContentSha256() != null) {
            throw new IllegalStateException("未取得内容的材料不应有摘要（持久化不变式冲突）："
                    + artifactKey + " 状态 " + saved.getAvailability());
        }
        // 来源不可用/不存在：登记问题提示人工，不得就此形成最终决定。
        if (!"RESOLVED".equals(saved.getAvailability())) {
            ensureIssue(caseId, null, "SOURCE_UNAVAILABLE:" + artifactKey + ":v1",
                    IssueSeverity.DECISION_CRITICAL, null,
                    "材料 " + artifactKey + " 未能从来源系统取得实际内容（状态 " + saved.getAvailability()
                            + "）；不能作为已核实依据形成最终决定，需重试、换替代来源或说明处理责任。",
                    actor);
        }
        // 新材料尚未关联单元：进入案件待分派事实清单（V2-12）。
        ensureIssue(caseId, null, "FACT_UNASSIGNED:" + artifactKey + ":v1", IssueSeverity.DECISION_CRITICAL,
                null, "新材料 " + artifactKey + " 尚未关联任何预警单元；最终决定前需明确关联或说明不相关理由。",
                actor);
        bumpEpoch(caseId, "ARTIFACT_CAPTURED:" + artifactKey + ":v1");
        auditOutbox.enqueue("EXPLANATION_ARTIFACT:" + caseId + ":" + artifactKey + ":v1",
                actor, "EXPLANATION_ARTIFACT_CAPTURED", "CASE", String.valueOf(caseId),
                "artifactKey=" + artifactKey + ",integrity=" + saved.getIntegrityStatus()
                        + ",availability=" + saved.getAvailability());
        return evidenceView(saved);
    }

    /** 记录具体核验动作：技术解析与人工作用判断分开；核验变更推进依据版本。 */
    @Transactional
    public ExplanationViews.VerificationView recordVerification(Long caseId, Long artifactVersionId,
                                                                String method, String observedFacts,
                                                                String limitations, String result,
                                                                String actor) {
        return recordVerification(caseId, artifactVersionId, method, observedFacts, limitations, result,
                actor, null);
    }

    /** FR-01：带核验对象（questionCode/claim 事实键）的核验记录；同一材料可面向不同事实分别核验。 */
    public ExplanationViews.VerificationView recordVerification(Long caseId, Long artifactVersionId,
                                                                String method, String observedFacts,
                                                                String limitations, String result,
                                                                String actor, String subjectFactKey) {
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
        event.setSubjectFactKey(subjectFactKey == null || subjectFactKey.isBlank()
                ? null : subjectFactKey.trim());
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
        // A6-05/RC-06：核验更正/撤销/失去支持（MISMATCH/UNRESOLVED）→ 依赖该材料版本的
        // 采用提交置 STALE（与材料内容变更同一失效通道）；刷新 token 只能更新版本，不能把事实缺口消掉。
        if ("MISMATCH".equals(normalizedResult) || "UNRESOLVED".equals(normalizedResult)) {
            List<Long> affected = staleSubmissionsUsingArtifact(caseId, artifactVersionId,
                    "VERIFICATION_LOST_SUPPORT:" + saved.getId() + ":" + normalizedResult, actor);
            if (!affected.isEmpty()) {
                auditOutbox.enqueue("EXPLANATION_STALE:" + caseId + ":" + artifactVersionId,
                        actor, "EXPLANATION_SUBMISSION_STALE", "CASE", String.valueOf(caseId),
                        "reason=verification_" + normalizedResult + ",verificationEventId=" + saved.getId()
                                + ",affectedSubmissions=" + affected);
            }
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
        List<Long> affected = staleSubmissionsUsingArtifact(caseId, artifactVersionId,
                "ARTIFACT_SUPERSEDED:" + artifact.getArtifactKey() + (reason == null ? "" : ":" + reason),
                actor);
        bumpEpoch(caseId, "ARTIFACT_SUPERSEDED:" + artifact.getArtifactKey());
        auditOutbox.enqueue("EXPLANATION_STALE:" + caseId + ":" + artifactVersionId,
                actor, "EXPLANATION_SUBMISSION_STALE", "CASE", String.valueOf(caseId),
                "artifactVersionId=" + artifactVersionId + ",affectedSubmissions=" + affected);
        return affected;
    }

    /** 按反向引用把引用该材料版本的 CURRENT 提交置 STALE 并复位覆盖（A5-05/TP-16 共用）。 */
    private List<Long> staleSubmissionsUsingArtifact(Long caseId, Long artifactVersionId,
                                                     String supersededReason, String actor) {
        List<Long> affected = new ArrayList<>();
        for (ExplanationEvidenceUse use : evidenceUseRepository
                .findByCaseIdAndArtifactVersionId(caseId, artifactVersionId)) {
            ExplanationSubmission submission = submissionRepository.findById(use.getSubmissionId())
                    .orElse(null);
            if (submission == null || submission.getState() != SubmissionState.CURRENT) {
                continue;
            }
            submission.setState(SubmissionState.STALE);
            submission.setSupersededReason(supersededReason);
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

        // 代付配方的缺口事实（如授权额度不足 / 部分订单未覆盖）在任何 outcome 下都要登记关键问题：
        // 不因"多数金额已解释"吞掉缺口（TP-10/TP-11），也不自动变可疑。
        if (ExplanationPolicyCatalog.GOODS_GROUP_PAYMENT_V1.equals(resolution.policyCode())) {
            registerGroupPaymentGaps(caseEntity, unit, draft, actor);
        }

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

        // 同事务持久化依据使用记录（A5-05）：材料/问题引用 → explanation_evidence_use；
        // 来源变更按本表反查受影响提交。无引用也写一条方向性记录，保证依赖链可查询。
        persistEvidenceUses(caseId, saved.getId(), draft, summary);

        // 同事务冻结核验依据版本（A5-01）：服务器可枚举交易集 + 范围摘要（可重算）。
        persistVerificationBasis(caseEntity, saved, draft);

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

    /**
     * 问题处置：解决 / 说明不相关 / 披露未解决。
     * 重要性降级不再随处置一步完成（A5-04）：客户端传入的 confirmedBy 无权威意义；
     * 降级必须通过 {@link #proposeDowngrade} + {@link #confirmDowngrade} 两步，
     * 由另一位已认证 REVIEWER/ADMIN 在独立请求中确认。
     */
    @Transactional
    public ExplanationViews.IssueView disposeIssue(Long caseId, Long issueId, int expectedRevision,
                                                   String disposition, String reason,
                                                   String evidenceReference, String actor) {
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
        issue.setRevision(issue.getRevision() + 1);
        issue.setUpdatedAt(LocalDateTime.now(clock));
        ExplanationIssue saved = issueRepository.save(issue);
        // 关键问题处置影响被采用的依据：推进案件事实序号（§10.1）。
        bumpEpoch(caseId, "ISSUE_DISPOSED:" + issueId + ":" + saved.getDisposition());
        auditOutbox.enqueue("EXPLANATION_ISSUE:" + caseId + ":" + issueId + ":" + saved.getRevision(),
                actor, "EXPLANATION_ISSUE_DISPOSITION", "CASE", String.valueOf(caseId),
                "issueKey=" + saved.getIssueKey() + ",disposition=" + saved.getDisposition()
                        + ",severity=" + saved.getSeverity());
        return issueView(saved);
    }

    /**
     * 降级提案第一步（A5-04）：分析员提交拟议降级；不改变问题现状。
     * 完整性问题不允许降级；提案记录原等级、理由与依据，等待独立复核人确认。
     */
    @Transactional
    public ExplanationViews.IssueReviewView proposeDowngrade(Long caseId, Long issueId, int expectedRevision,
                                                             String downgradeTo, String reason,
                                                             String evidenceReference, String actor) {
        CaseEntity caseEntity = requireLockedExplanationCase(caseId);
        ExplanationIssue issue = issueRepository.findByIdAndCaseId(issueId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("问题不存在：" + issueId));
        if (issue.getRevision() != expectedRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, issueId,
                    issue.getRevision(),
                    "问题已被他人处置（当前版本 " + issue.getRevision() + "），请刷新后重试");
        }
        IssueSeverity target = parseEnum(IssueSeverity.class, downgradeTo, "目标重要性");
        if (target == IssueSeverity.INTEGRITY_BLOCKER || issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER) {
            throw new IllegalArgumentException("完整性/身份错误不允许降级");
        }
        // 提案基于问题现状：已处置（RESOLVED/DISCLOSED/NOT_RELEVANT）的问题不再提案降级，
        // 需先重开（REOPEN）再评估（A5-04 审查补强）。
        if (issue.getDisposition() != IssueDisposition.OPEN) {
            throw new IllegalStateException("问题当前处置状态为 " + issue.getDisposition()
                    + "，不能提案降级；请先重开问题");
        }
        if (rank(target) >= rank(issue.getSeverity())) {
            throw new IllegalArgumentException("只能向更低重要性降级");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 20) {
            throw new IllegalArgumentException("降级需记录原等级、理由和引用（至少 20 个字符）");
        }
        // 同一问题已有待确认提案时不得重复创建（确认或拒绝后再提）。
        if (!issueReviewRepository.findByCaseIdAndStatusOrderByIdAsc(caseId, "PENDING").stream()
                .filter(review -> review.getIssueId().equals(issueId)).toList().isEmpty()) {
            throw new IllegalStateException("该问题已有待确认的降级提案；请等待确认或拒绝后重新提案");
        }
        ExplanationIssueReview review = new ExplanationIssueReview();
        review.setCaseId(caseId);
        review.setIssueId(issueId);
        review.setProposalRevision(expectedRevision);
        review.setIssueRevision(expectedRevision);
        review.setOriginalSeverity(issue.getSeverity());
        review.setProposedSeverity(target);
        review.setReason(normalizedReason);
        review.setEvidenceReference(evidenceReference == null || evidenceReference.isBlank()
                ? null : evidenceReference.trim());
        review.setProposedBy(actor);
        review.setProposedAt(LocalDateTime.now(clock));
        review.setStatus("PENDING");
        ExplanationIssueReview saved = issueReviewRepository.save(review);
        auditOutbox.enqueue("EXPLANATION_DOWNGRADE_PROPOSAL:" + caseId + ":" + issueId + ":" + saved.getId(),
                actor, "EXPLANATION_ISSUE_DOWNGRADE_PROPOSED", "CASE", String.valueOf(caseId),
                "issueId=" + issueId + ",from=" + issue.getSeverity() + ",to=" + target);
        return issueReviewView(saved);
    }

    /**
     * 降级确认第二步（A5-04）：另一位已认证 REVIEWER/ADMIN 独立确认。
     * 确认人来自认证上下文与 UserAccountRepository 核对（角色与启用状态）；
     * 不得是提案人；提案与问题版本必须与提案时一致；确认后问题等级才真正改变。
     */
    @Transactional
    public ExplanationViews.IssueView confirmDowngrade(Long caseId, Long proposalId,
                                                       int expectedProposalRevision, int expectedIssueRevision,
                                                       String confirmNote, String reviewer) {
        requireLockedExplanationCase(caseId);
        ExplanationIssueReview review = issueReviewRepository.findByIdAndCaseId(proposalId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("降级提案不存在：" + proposalId));
        if (!"PENDING".equals(review.getStatus())) {
            throw new IllegalStateException("该提案已被处理（当前状态 " + review.getStatus() + "）");
        }
        if (review.getProposalRevision() != expectedProposalRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, proposalId, null,
                    "提案已被他人处理，请刷新后重试");
        }
        // 独立复核人核对：认证身份必须存在、启用且具有 REVIEWER/ADMIN 角色（A5-04）。
        String identity = reviewer == null ? "" : reviewer.trim();
        com.bank.aml.security.UserAccount account = userAccountRepository.findByUsername(identity)
                .filter(user -> user.isEnabled())
                .filter(user -> java.util.Set.of("REVIEWER", "ADMIN").contains(user.getRole()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "确认人必须为已启用的 REVIEWER/ADMIN 账户；当前身份无权确认降级"));
        if (account.getUsername().equals(review.getProposedBy())) {
            throw new IllegalArgumentException("降级提案人不能确认自己的提案（双人确认，A5-04）");
        }
        ExplanationIssue issue = issueRepository.findByIdAndCaseId(review.getIssueId(), caseId)
                .orElseThrow(() -> new IllegalArgumentException("问题不存在：" + review.getIssueId()));
        if (issue.getRevision() != expectedIssueRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, issue.getId(), null,
                    "问题版本已变化（当前 " + issue.getRevision() + "），提案基于版本 "
                            + expectedIssueRevision + "；请重新评估并另提提案");
        }
        // 确认身份由服务端写入：客户端不再具有指定 confirmedBy 的权威意义。
        issue.setSeverity(review.getProposedSeverity());
        issue.setConfirmedBy(account.getUsername());
        issue.setRevision(issue.getRevision() + 1);
        issue.setUpdatedAt(LocalDateTime.now(clock));
        ExplanationIssue saved = issueRepository.save(issue);
        review.setStatus("CONFIRMED");
        review.setConfirmedBy(account.getUsername());
        review.setConfirmedAt(LocalDateTime.now(clock));
        review.setConfirmNote(confirmNote == null ? null : confirmNote.trim());
        issueReviewRepository.save(review);
        bumpEpoch(caseId, "ISSUE_DOWNGRADE_CONFIRMED:" + saved.getId() + ":" + saved.getSeverity());
        auditOutbox.enqueue("EXPLANATION_DOWNGRADE_CONFIRMED:" + caseId + ":" + saved.getId(),
                account.getUsername(), "EXPLANATION_ISSUE_DOWNGRADE_CONFIRMED", "CASE", String.valueOf(caseId),
                "issueId=" + saved.getId() + ",severity=" + saved.getSeverity()
                        + ",proposalId=" + proposalId);
        return issueView(saved);
    }

    /** 拒绝降级提案：问题现状不变；记录拒绝理由与拒绝人。 */
    @Transactional
    public ExplanationViews.IssueReviewView rejectDowngrade(Long caseId, Long proposalId,
                                                            int expectedProposalRevision,
                                                            String rejectedReason, String reviewer) {
        requireLockedExplanationCase(caseId);
        ExplanationIssueReview review = issueReviewRepository.findByIdAndCaseId(proposalId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("降级提案不存在：" + proposalId));
        if (!"PENDING".equals(review.getStatus())) {
            throw new IllegalStateException("该提案已被处理（当前状态 " + review.getStatus() + "）");
        }
        if (review.getProposalRevision() != expectedProposalRevision) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, proposalId, null,
                    "提案已被他人处理，请刷新后重试");
        }
        String identity = reviewer == null ? "" : reviewer.trim();
        com.bank.aml.security.UserAccount account = userAccountRepository.findByUsername(identity)
                .filter(user -> user.isEnabled())
                .filter(user -> java.util.Set.of("REVIEWER", "ADMIN").contains(user.getRole()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "拒绝人必须为已启用的 REVIEWER/ADMIN 账户"));
        if (account.getUsername().equals(review.getProposedBy())) {
            throw new IllegalArgumentException("提案人不能处理自己的提案");
        }
        review.setStatus("REJECTED");
        review.setRejectedReason(rejectedReason == null ? "" : rejectedReason.trim());
        review.setConfirmedBy(account.getUsername());
        review.setConfirmedAt(LocalDateTime.now(clock));
        issueReviewRepository.save(review);
        auditOutbox.enqueue("EXPLANATION_DOWNGRADE_REJECTED:" + caseId + ":" + proposalId,
                account.getUsername(), "EXPLANATION_ISSUE_DOWNGRADE_REJECTED", "CASE", String.valueOf(caseId),
                "issueId=" + review.getIssueId());
        return issueReviewView(review);
    }

    // ==================== 定向核验建议（v3 计划 §7） ====================

    /**
     * 下一动作建议（S4）：可解释的规则排序，不训练风险分数。
     * 排序：来源/身份完整性(1) → 会改变决定的矛盾(2) → 决定关键未知(3) →
     * 即将到期义务(4) → 补充背景(5)。相同优先级先复用已取得且仍有效的资料。
     */
    @Transactional(readOnly = true)
    public List<ExplanationViews.NextActionView> nextActions(Long caseId, Long unitId) {
        CaseEntity caseEntity = requireCase(caseId);
        AlertExplanationUnit unit = requireUnit(caseId, unitId);
        List<ExplanationViews.NextActionView> actions = new ArrayList<>();
        List<ExplanationIssue> issues = issueRepository.findByCaseIdAndUnitIdOrderByIdAsc(caseId, unitId);
        List<ExplanationIssue> caseIssues = issueRepository.findByCaseIdOrderByIdAsc(caseId);

        // 1. 完整性/身份错误：不能以被破坏的依据形成任何决定
        for (ExplanationIssue issue : caseIssues) {
            if (issue.getDisposition() == IssueDisposition.OPEN
                    && issue.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER) {
                actions.add(new ExplanationViews.NextActionView(1, "INTEGRITY", null,
                        issue.getIssueKey(), "可用的材料来源（当前完整性被破坏）",
                        issue.getIssueKey().startsWith("INTEGRITY") ? "原来源系统" : "来源系统",
                        "更正来源后重新抓取该材料，并记录更正原因",
                        "决定能否以该材料形成任何结论",
                        "来源系统更正后重新抓取；无替代来源则该材料退出依据"));
            }
        }
        // 2. 已评估矛盾（CONTRADICTED claims）：会改变当前决定
        JsonNode draft = unit.getDraftJson() == null ? null : parseQuiet(unit.getDraftJson());
        if (draft != null && draft.get("claims") != null && draft.get("claims").isObject()) {
            JsonNode claims = draft.get("claims");
            for (String code : new String[]{"C1", "C2", "C3", "C4", "C5", "C6"}) {
                JsonNode claim = claims.get(code);
                if (claim == null || !claim.isObject()) {
                    continue;
                }
                String status = text(claim, "status", "UNASSESSED");
                if ("CONTRADICTED".equals(status)) {
                    actions.add(new ExplanationViews.NextActionView(2, "CONTRADICTION", code,
                            null, "矛盾的反向解释（客户或来源能否解释该矛盾）",
                            "与矛盾事实直接相关的权威来源",
                            "核对矛盾事实的身份与回复范围，要求客户回应矛盾；保留否认事实",
                            "单元结论方向（EXPLAINED/SUSPICIOUS）",
                            "无：矛盾必须由人工判断处理，不能用同源材料覆盖"));
                } else if (("UNASSESSED".equals(status) || "UNRESOLVED".equals(status))
                        && List.of("C1", "C2", "C3", "C4").contains(code)) {
                    // 3. 决定关键未知：定向核验
                    actions.add(new ExplanationViews.NextActionView(3, "CRITICAL_UNKNOWN", code,
                            null, factQuestion(code), factSuggestedSource(code),
                            factSuggestedAction(code),
                            "该事实是否被支持（决定解释能否成立）",
                            factAlternative(code)));
                }
            }
            // 授权额度缺口
            JsonNode authority = draft.get("authority");
            if (authority != null && authority.isObject() && !text(authority, "limitAmount").isBlank()) {
                try {
                    java.math.BigDecimal limit = new java.math.BigDecimal(text(authority, "limitAmount"));
                    java.math.BigDecimal covered = java.math.BigDecimal.ZERO;
                    Map<String, java.math.BigDecimal> sourceAmounts = serverTransactionAmounts(caseEntity);
                    JsonNode coveredTxs = authority.get("coveredTransactionIds");
                    if (coveredTxs != null && coveredTxs.isArray()) {
                        for (JsonNode tx : coveredTxs) {
                            java.math.BigDecimal amount = sourceAmounts.get(tx.asText());
                            if (amount != null) {
                                covered = covered.add(amount);
                            }
                        }
                    }
                    if (limit.subtract(covered).compareTo(java.math.BigDecimal.ZERO) < 0) {
                        actions.add(new ExplanationViews.NextActionView(3, "AUTHORITY_GAP", "C3",
                                null, "超出授权额度的交易部分（缺口）",
                                "买方授权追加记录或逐笔拆分授权",
                                "针对超出部分询问授权追加或其他付款性质；不得重复核验已覆盖部分",
                                "超出部分能否并入解释（TP-10：不得整笔解释成立）",
                                "超限部分提交 UNRESOLVED，保留缺口供人工判断"));
                    }
                } catch (NumberFormatException ignored) {
                    // 额度格式错误已在提交校验拒绝
                }
            }
        }
        // 4. OPEN 决策支持任务即将到期：提醒既有义务
        for (EnhancedDueDiligenceRequest task : eddRepository
                .findByCaseIdAndStatusOrderByIdAsc(caseId, EnhancedDueDiligenceStatus.OPEN)) {
            if (task.getPurpose() == EddTaskPurpose.DECISION_SUPPORT && task.getDueAt() != null) {
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.now(clock), task.getDueAt().toLocalDate());
                if (daysLeft <= 5) {
                    actions.add(new ExplanationViews.NextActionView(4, "DUE_SOON", null,
                            "EDD#" + task.getId(), "第 " + task.getRoundNo() + " 轮补件（"
                            + task.getDueAt().toLocalDate() + " 到期）",
                            "原补件要求中尚未提交的材料项",
                            "跟进承办人（" + task.getAssignedTo() + "）或明确等待责任",
                            "义务是否按时履行（逾期进入运营队列）",
                            "复核人可撤销或调整任务（记录原因）"));
                }
            }
        }
        // 5. 案件级待分派事实
        for (ExplanationIssue issue : caseIssues) {
            if (issue.getUnitId() == null && issue.getDisposition() == IssueDisposition.OPEN
                    && issue.getSeverity() == IssueSeverity.DECISION_CRITICAL) {
                actions.add(new ExplanationViews.NextActionView(5, "UNASSIGNED_FACT", null,
                        issue.getIssueKey(), "该事实与预警单元的关联（或不相关认定）",
                        "事实来源对应的业务系统",
                        "把事实关联到具体单元，或说明与本次决定不相关的理由",
                        "案件能否形成最终决定（待分派清单必须为空）",
                        "无：必须关联或说明，不能静默跳过"));
            }
        }
        actions.sort(java.util.Comparator.comparingInt(ExplanationViews.NextActionView::priority));
        return actions;
    }

    /** 重复补件提醒（v3 计划 §7）：同事实键（issueKey 去除轮次后缀）已出现 ≥2 次 OPEN→处置记录。 */
    @Transactional(readOnly = true)
    public boolean repeatedEvidenceRequest(Long caseId, Long unitId, String factKey) {
        List<ExplanationIssue> issues = issueRepository.findByCaseIdAndUnitIdOrderByIdAsc(caseId, unitId);
        long count = issues.stream()
                .filter(issue -> issue.getIssueKey() != null
                        && issue.getIssueKey().contains(factKey)
                        && issue.getDisposition() != IssueDisposition.OPEN)
                .count();
        return count >= 2;
    }

    private static String factQuestion(String code) {
        return switch (code) {
            case "C1" -> "付款账户所属主体与合同买方是否同一法定主体（主体消歧）";
            case "C2" -> "买方因哪项交付对卖方负有多少货款义务";
            case "C3" -> "谁授权谁、向谁、付哪笔、多少、何时有效";
            default -> "这两笔钱是否在授权范围内履行了授权所指义务";
        };
    }

    private static String factSuggestedSource(String code) {
        return switch (code) {
            case "C1" -> "核心系统账户归属 + KYC 主体标识";
            case "C2" -> "订单/履约/验收记录";
            case "C3" -> "可定位的授权版本及独立来源确认";
            default -> "权威流水与授权/订单的逐笔分配";
        };
    }

    private static String factSuggestedAction(String code) {
        return switch (code) {
            case "C1" -> "查询付款账户所属主体，核对买方历史名称后再判断是否代付";
            case "C2" -> "核对指定订单与交付记录，不泛要全部财务资料";
            case "C3" -> "对授权的签发、范围、撤销状态做一次独立确认";
            default -> "核对无法解释的具体交易与超限差额";
        };
    }

    private static String factAlternative(String code) {
        return switch (code) {
            case "C1" -> "同名/简称/历史名称先做主体消歧；确认同一主体回到普通货款流程";
            case "C2" -> "往来核对记录可作为替代（单独一张发票不能覆盖全部结论）";
            case "C3" -> "预先核实渠道取得的买方确认（联系电话不能仅来自本次可疑材料）";
            default -> "买方或收款方入账用途核对";
        };
    }

    private JsonNode parseQuiet(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 就绪与最终复核依据 ====================

    /** v2 案件的就绪结论（混合结论按决策表执行；供详情页与运营队列复用）。 */
    @Transactional(readOnly = true)
    public InvestigationReadinessResult readinessResult(Long caseId) {
        CaseEntity caseEntity = requireCase(caseId);
        return evaluateReadiness(caseEntity, null);
    }

    /** 按当前操作者视角的就绪结论：canExclude/canConfirm 含自审限制（v3 计划 §8）。 */
    @Transactional(readOnly = true)
    public InvestigationReadinessResult readinessResultForReviewer(Long caseId, String reviewer) {
        CaseEntity caseEntity = requireCase(caseId);
        return evaluateReadiness(caseEntity, reviewer);
    }

    /**
     * 仅校验依据令牌（A5-07 顺序修复）：在案件锁内、任何任务变更之前调用；
     * 用户提交时看到的令牌必须与当前案件事实一致。
     */
    @Transactional(readOnly = true)
    public void validateReviewBasisToken(Long caseId, String reviewBasisToken) {
        String expectedToken = reviewBasisToken(caseId);
        if (reviewBasisToken == null || !reviewBasisToken.trim().equals(expectedToken)) {
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, caseId, null,
                    "最终复核依据令牌已失效（案件事实在取号后已变化），请重新获取复核依据");
        }
    }

    /**
     * 最终复核前校验：决策表 + 令牌 + 实质贡献人自审限制。
     * A5-06 修复：预付交期按注入 Clock 在最终事务内即时重评（不再读取提交时冻结的布尔值）；
     * 交期已过且无交付/延期依据 → 恢复关键待核验问题并阻断排除。任务覆盖由
     * {@link #validateObligationCoverage} 按当前 OPEN 任务逐义务检查。
     * tokenAlreadyValidated=true 时跳过令牌比较（A5-07：本事务的接续变更会使 token 变化，
     * 不拿变更前 token 与变更后事实作等值比较；令牌已在变更前由 validateReviewBasisToken 校验）。
     */
    public void validateReadyForReview(CaseEntity caseEntity, ReviewDecision decision,
                                       String reviewer, String reviewBasisToken) {
        validateReadyForReview(caseEntity, decision, reviewer, reviewBasisToken, false);
    }

    public void validateReadyForReview(CaseEntity caseEntity, ReviewDecision decision,
                                       String reviewer, String reviewBasisToken,
                                       boolean tokenAlreadyValidated) {
        if (decision == ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) {
            return;
        }
        // 时间即时重评（A5-06）：预付交期在最终事务内按当前 Clock 重新评估。
        reevaluatePrepayDue(caseEntity);
        // A6-03/RC-07：逐义务覆盖校验——从 CURRENT 提交提取 followupRequired 义务，
        // 逐项匹配 OPEN CONTINUING_REVIEW 任务（同案件、有效承办人、未来期限、完成标准）。
        // 能力值由实际覆盖计算：禁止通过"处于最终事务"推定任务已经安排。
        List<String> obligationBlockers = validateObligationCoverage(caseEntity);
        // 义务接续已在同一事务内先行完成（ReviewService 先调用 transferObligations），
        // 因此按"接续已安排"口径评估任务门槛；OPEN DECISION_SUPPORT 且无计划时仍被阻断。
        InvestigationReadinessResult readiness = evaluateReadiness(caseEntity, reviewer, true);
        List<String> blockers = switch (decision) {
            case CONFIRM_SUSPICIOUS -> readiness.confirmBlockers();
            case EXCLUDE_FALSE_POSITIVE -> readiness.excludeBlockers();
            default -> List.of();
        };
        // 逐义务覆盖缺口进入最终 blocker（A6-03）：任一 followupRequired 义务无承接任务，
        // 确认与排除都被阻断——"未到期"不等于"已安排"。
        List<String> combined = new ArrayList<>(blockers);
        combined.addAll(obligationBlockers);
        if (!combined.isEmpty()) {
            throw new IllegalStateException("调查尚未满足最终处置条件：" + String.join("；", combined));
        }
        if (!tokenAlreadyValidated) {
            String expectedToken = reviewBasisToken(caseEntity.getId());
            if (reviewBasisToken == null || !reviewBasisToken.trim().equals(expectedToken)) {
                throw new InvestigationRevisionConflictException(
                        InvestigationRevisionConflictException.TYPE_COVERAGE, caseEntity.getId(), null,
                        "最终复核依据令牌已失效（案件事实在取号后已变化），请重新获取复核依据");
            }
        }
    }

    /**
     * A5-06：预付配方的交期在最终事务内即时重评。
     * 交期已过且未提供交付/合理延期依据 → 恢复 DELIVERY_OVERDUE 关键问题并推进 epoch；
     * 该问题随后阻断最终排除（不自动变可疑，由人工判断）。
     */
    /**
     * 逐义务覆盖校验（A6-03/RC-07）：
     * 从 CURRENT 提交提取 followupRequired 义务（预付交期跟进等），
     * 逐项匹配 OPEN 的 CONTINUING_REVIEW 任务：同案件、有效承办人（启用 ANALYST/ADMIN）、
     * 期限在未来、完成标准非空。任意一项无承接 → 义务缺口 blocker。
     * 一条 CONTINUING_REVIEW 不能覆盖整个案件的多项义务（每项义务至少一个专属承接）。
     */
    /**
     * 逐义务覆盖校验（FR-03/v4 §4.3 强化）：
     * 从 CURRENT 提交的 payload 派生义务事实键（如 DELIVERY:PO-001 / REFUND_AUTHORITY:T-1002），
     * 逐项匹配 OPEN CONTINUING_REVIEW 任务——同案件、同 obligationFactKey、有效承办人、
     * 未来期限、完成标准。错绑任务（factKey 不匹配）视为未覆盖（RF-04）。
     * 无 factKey 的存量任务不参与新语义匹配（不能因为任务数等于义务数就视为覆盖）。
     */
    private List<String> validateObligationCoverage(CaseEntity caseEntity) {
        List<EnhancedDueDiligenceRequest> openTasks = eddRepository
                .findByCaseIdAndStatusOrderByIdAsc(caseEntity.getId(),
                        EnhancedDueDiligenceStatus.OPEN).stream()
                .filter(task -> task.getPurpose() == EddTaskPurpose.CONTINUING_REVIEW)
                .toList();
        List<String> blockers = new ArrayList<>();
        Set<Long> claimedTaskIds = new LinkedHashSet<>();
        Set<Long> assessedSubmissionIds = new LinkedHashSet<>();
        for (ExplanationSubmission current : submissionRepository
                .findByCaseIdAndStateOrderByIdAsc(caseEntity.getId(), SubmissionState.CURRENT)) {
            if (!current.isFollowupRequired() || !assessedSubmissionIds.add(current.getId())) {
                continue; // 每个采用提交评估一次（同事务内重复 save 不重复计义务）
            }
            List<String> obligationKeys = deriveObligationKeys(current);
            if (obligationKeys.isEmpty()) {
                // payload 未声明义务键（存量提交）：保持提交级义务语义（一项提交一项义务）
                obligationKeys = List.of("SUBMISSION:" + current.getId());
            }
            for (String obligationKey : obligationKeys) {
                EnhancedDueDiligenceRequest matched = null;
                for (EnhancedDueDiligenceRequest task : openTasks) {
                    if (claimedTaskIds.contains(task.getId())) {
                        continue; // 每项义务至少一个专属承接；一条任务不覆盖多项义务
                    }
                    // FR-03：义务事实键必须匹配——用无关任务承接交付/退款义务视为未覆盖
                    if (!obligationKey.equals(task.getObligationFactKey())) {
                        continue;
                    }
                    boolean assignedOk = task.getAssignedTo() != null
                            && userAccountRepository.findByUsername(task.getAssignedTo())
                            .filter(user -> user.isEnabled())
                            .filter(user -> java.util.Set.of("ANALYST", "ADMIN").contains(user.getRole()))
                            .isPresent();
                    boolean dueOk = task.getDueAt() != null
                            && task.getDueAt().isAfter(LocalDateTime.now(clock));
                    boolean standardOk = task.getCompletionStandard() != null
                            && task.getCompletionStandard().trim().length() >= 10;
                    if (assignedOk && dueOk && standardOk) {
                        matched = task;
                        claimedTaskIds.add(task.getId());
                        break;
                    }
                }
                if (matched == null) {
                    blockers.add("义务 " + obligationKey + "（提交 #" + current.getId() + "）无有效承接任务"
                            + "（需要：同案件 OPEN CONTINUING_REVIEW、obligationFactKey 匹配 " + obligationKey
                            + "、有效承办人、未来期限、明确完成标准）；未到期不等于已安排、"
                            + "错绑任务不能替代本义务（FR-03/RF-04）");
                }
            }
        }
        return blockers;
    }

    /** 从提交 payload 派生义务事实键（v4 §4.3）：预付交期 → DELIVERY:{contractNumber}。 */
    private List<String> deriveObligationKeys(ExplanationSubmission submission) {
        try {
            JsonNode draft = objectMapper.readTree(submission.getPayloadJson());
            JsonNode policy = draft.get("policy");
            if (policy != null && policy.isObject()
                    && ExplanationPolicyCatalog.STAGE_ADVANCE_PAYMENT.equalsIgnoreCase(
                    text(policy, "paymentStage"))) {
                String contract = text(policy, "contractNumber");
                if (!contract.isBlank()) {
                    return List.of("DELIVERY:" + contract.trim());
                }
            }
        } catch (Exception ignored) {
            // payload 损坏 → 空列表走提交级兜底键
        }
        return List.of();
    }

    private void reevaluatePrepayDue(CaseEntity caseEntity) {
        for (AlertExplanationUnit unit : unitRepository.findByCaseIdOrderByIdAsc(caseEntity.getId())) {
            if (unit.getCurrentSubmissionId() == null
                    || !ExplanationPolicyCatalog.GOODS_PREPAY_V1.equals(unit.getPolicyCode())) {
                continue;
            }
            ExplanationSubmission submission = submissionRepository
                    .findById(unit.getCurrentSubmissionId()).orElse(null);
            if (submission == null || submission.getState() != SubmissionState.CURRENT
                    || !submission.isFollowupRequired()) {
                continue;
            }
            try {
                JsonNode draft = objectMapper.readTree(submission.getPayloadJson());
                ExplanationPolicyCatalog.Applicability applicability = readApplicability(draft);
                LocalDate today = LocalDate.now(clock);
                if (!policyCatalog.deliveryNotYetDue(applicability, today)) {
                    // 交期已过：恢复关键问题（幂等 ensureIssue），阻断最终排除直至人工解决。
                    ensureIssue(caseEntity.getId(), unit.getId(), "DELIVERY_OVERDUE:" + unit.getId(),
                            IssueSeverity.DECISION_CRITICAL, "Q3",
                            "最终复核时重评：预付款约定交期已过且未提供交付或合理延期依据；"
                                    + "不能继续按 NOT_YET_DUE 排除，需人工核验履约情况。",
                            "system");
                    bumpEpoch(caseEntity.getId(), "PREPAY_DUE_REEVALUATED:" + unit.getId());
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("提交 payload 损坏，不能评估交期：" + unit.getId(), e);
            }
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
        // 案件级待分派事实（unitId=null 的 DECISION_CRITICAL，如 FACT_UNASSIGNED / 反证）：
        // 清单非空时不得形成任何最终决定（A5-03）；必须先完成关联或有依据的不相关认定。
        List<ExplanationIssue> unassignedCritical = issues.stream()
                .filter(issue -> issue.getUnitId() == null
                        && issue.getDisposition() == IssueDisposition.OPEN
                        && issue.getSeverity() == IssueSeverity.DECISION_CRITICAL)
                .toList();
        if (!unassignedCritical.isEmpty()) {
            general.add("存在 " + unassignedCritical.size() + " 项案件级待分派关键事实（未关联预警单元）："
                    + unassignedCritical.stream().map(ExplanationIssue::getIssueKey)
                    .reduce((a, b) -> a + "、" + b).orElse("")
                    + "；需先关联到具体预警单元或给出有依据的不相关认定，然后才能形成最终决定");
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
        // v3 计划 §8：canExclude/canConfirm 必须同时考虑当前操作者独立性——
        // blocker 显示自审冲突时能力值不得为 true（防止 UI 按钮可用性与阻断列表矛盾）。
        boolean canExclude = decision.canExclude() && (reviewer == null || reviewerIndependent);
        boolean canConfirm = decision.canConfirm() && (reviewer == null || reviewerIndependent);
        return new InvestigationReadinessResult(ready, dedupe(general), dedupe(confirm), dedupe(exclude),
                canExclude, canConfirm, reviewerIndependent);
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

    /**
     * 代付配方的事实约束（v3 计划 §6/§8；TP-06/TP-09）：
     * 草稿中 claims 节声明 C1~C6 状态；EXPLAINED 要求 C1~C4 全部 SUPPORTED。
     * C5/C6 允许 UNRESOLVED（保留未知）但不允许 CONTRADICTED（矛盾必须先处理）。
     */
    private void validateGroupPaymentClaims(CaseEntity caseEntity, JsonNode draft) {
        JsonNode claims = draft.get("claims");
        if (claims == null || !claims.isObject()) {
            throw new IllegalArgumentException("集团代付配方需声明 C1~C6 事实（claims）");
        }
        for (String code : new String[]{"C1", "C2", "C3", "C4"}) {
            JsonNode claim = claims.get(code);
            if (claim == null || !claim.isObject()) {
                throw new IllegalArgumentException("集团代付配方必须回答事实 " + code
                        + "（C1~C4 不可整体跳过）");
            }
            String status = text(claim, "status", "UNASSESSED");
            if (!Set.of("SUPPORTED", "CONTRADICTED", "UNRESOLVED", "NOT_APPLICABLE", "UNASSESSED")
                    .contains(status)) {
                throw new IllegalArgumentException("事实 " + code + " 状态不在允许范围：" + status);
            }
            if (!"SUPPORTED".equals(status)) {
                throw new IllegalArgumentException("事实 " + code + " 状态为 " + status
                        + "，不能建议 EXPLAINED；请先完成该事实的定向核验，"
                        + "或提交 UNRESOLVED/SUSPICIOUS 保留判断");
            }
            String judgement = text(claim, "judgement");
            if (judgement.length() < 10) {
                throw new IllegalArgumentException("事实 " + code + " 的判断需至少 10 个字符"
                        + "（为什么证据支持该事实）");
            }
        }
        for (String code : new String[]{"C5", "C6"}) {
            JsonNode claim = claims.get(code);
            if (claim == null || !claim.isObject()) {
                continue; // C5/C6 可省略（省略视为未评估，不阻断 EXPLAINED，由 Q5/Q6 承担）
            }
            String status = text(claim, "status", "UNASSESSED");
            if ("CONTRADICTED".equals(status)) {
                throw new IllegalArgumentException("事实 " + code + " 存在已评估矛盾（CONTRADICTED），"
                        + "不能建议 EXPLAINED；需先处理矛盾或改判 SUSPICIOUS");
            }
        }
        // A6-02/RC-05：授权必填——C3 SUPPORTED 不能凭空成立；authority 结构、
        // 可定位授权编号、额度与覆盖集合缺一即拒，不能整体省略。
        JsonNode authority = draft.get("authority");
        if (authority == null || !authority.isObject()) {
            throw new IllegalArgumentException("集团代付配方必须声明代付授权（authority）："
                    + "可定位授权编号、额度与覆盖交易集合；C3 的 SUPPORTED 声明不能替代授权记录（A6-02）");
        }
        String authorityRef = text(authority, "authorityRef");
        if (authorityRef.length() < 3) {
            throw new IllegalArgumentException("代付授权需提供可定位的授权编号（authorityRef，"
                    + "至少 3 个字符）；授权身份缺失时 C3 不能成立");
        }
        String limitText = text(authority, "limitAmount");
        if (limitText.isBlank()) {
            throw new IllegalArgumentException("代付授权需声明额度（limitAmount）；无额度的授权"
                    + "不能确定覆盖边界（A6-02）");
        }
        java.math.BigDecimal limit;
        try {
            limit = new java.math.BigDecimal(limitText.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("授权额度需为定点数字符串：" + limitText);
        }
        // 服务端派生需要代付解释的资金腿全集（不从客户端 coveredTransactionIds 反向定义）：
        // 集合 = 本次提交 scope.reviewedTransactionIds（提交前已通过服务器来源对账）。
        JsonNode scopeNode = draft.get("scope");
        List<String> requiredLegs = scopeNode == null ? List.of()
                : stringList(scopeNode, "reviewedTransactionIds");
        JsonNode coveredTxs = authority.get("coveredTransactionIds");
        if (coveredTxs == null || !coveredTxs.isArray() || coveredTxs.size() == 0) {
            throw new IllegalArgumentException("代付授权需声明覆盖交易集合（coveredTransactionIds）；"
                    + "空覆盖不能支撑 C4 的 SUPPORTED（A6-02）");
        }
        Map<String, java.math.BigDecimal> sourceAmounts = serverTransactionAmounts(caseEntity);
        Set<String> coveredSet = new LinkedHashSet<>();
        java.math.BigDecimal covered = java.math.BigDecimal.ZERO;
        for (JsonNode tx : coveredTxs) {
            String txId = tx.asText();
            java.math.BigDecimal amount = sourceAmounts.get(txId);
            if (amount == null) {
                throw new IllegalArgumentException("授权覆盖的交易 " + txId
                        + " 不属于服务器冻结的交易来源集合");
            }
            if (!coveredSet.add(txId)) {
                throw new IllegalArgumentException("授权覆盖集合存在重复交易：" + txId
                        + "（去重计量，TP-31）");
            }
            covered = covered.add(amount);
        }
        // 逐笔比对：全部需要解释的代付资金腿必须被授权覆盖；未覆盖的具体交易保留缺口
        //（TP-11：第二笔不得继承第一笔结论；TP-10：不得整笔解释成立）。
        List<String> uncovered = requiredLegs.stream()
                .filter(tx -> !coveredSet.contains(tx)).toList();
        if (!uncovered.isEmpty()) {
            throw new IllegalArgumentException("以下待解释的代付资金腿未被授权覆盖："
                    + String.join("、", uncovered) + "；未覆盖金额不能隐去（A6-02/TP-11），"
                    + "需补充授权或把缺口交易移出命中范围（后者需来源更正）");
        }
        // 授权额度缺口：授权额度 < 覆盖交易合计 → 超出部分保留缺口，不得整笔解释成立（TP-10）。
        java.math.BigDecimal gap = limit.subtract(covered);
        if (gap.compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("代付授权额度 " + limit.toPlainString()
                    + " 低于已覆盖交易合计 " + covered.toPlainString() + "；超出部分 "
                    + gap.negate().toPlainString() + " 保留缺口，不得整笔解释成立"
                    + "（TP-10）；请提交 UNRESOLVED 并说明缺口处理安排");
        }
    }

    /**
     * 代付配方的缺口登记（TP-10/TP-11/TP-12 语义）：
     * 授权额度不足、部分订单未被授权覆盖、授权状态 CONTRADICTED 等，
     * 任何 outcome 下都产生 DECISION_CRITICAL 问题；不因多数金额已解释吞掉缺口。
     * 跨单元额度去重（v3 §6.1/TP-31）：同一交易不得被两个 CURRENT 代付提交重复声明覆盖。
     */
    private void registerGroupPaymentGaps(CaseEntity caseEntity, AlertExplanationUnit unit,
                                          JsonNode draft, String actor) {
        JsonNode claims = draft.get("claims");
        // 跨单元重复声明（TP-31）：同一交易已在其他 CURRENT 代付提交的 authority.coveredTransactionIds
        // 中出现 → 登记关键问题；授权使用量按唯一交易去重，重复提交不能规避额度上限。
        JsonNode authorityForDup = draft.get("authority");
        if (authorityForDup != null && authorityForDup.isObject()
                && authorityForDup.get("coveredTransactionIds") != null
                && authorityForDup.get("coveredTransactionIds").isArray()) {
            Set<String> declared = new LinkedHashSet<>();
            authorityForDup.get("coveredTransactionIds").forEach(tx -> declared.add(tx.asText()));
            Set<String> overlaps = new LinkedHashSet<>();
            for (ExplanationSubmission other : submissionRepository
                    .findByCaseIdAndStateOrderByIdAsc(caseEntity.getId(), SubmissionState.CURRENT)) {
                if (other.getUnitId() != null && other.getUnitId().equals(unit.getId())) {
                    continue;
                }
                try {
                    JsonNode otherDraft = objectMapper.readTree(other.getPayloadJson());
                    JsonNode otherAuthority = otherDraft.get("authority");
                    JsonNode otherCovered = otherAuthority == null ? null
                            : otherAuthority.get("coveredTransactionIds");
                    if (otherCovered == null || !otherCovered.isArray()) {
                        continue;
                    }
                    otherCovered.forEach(tx -> {
                        String txId = tx.asText();
                        if (declared.contains(txId)) {
                            overlaps.add(txId);
                        }
                    });
                } catch (Exception ignored) {
                    // payload 损坏的提交不参与去重（其本身已不可采用）
                }
            }
            if (!overlaps.isEmpty()) {
                // TP-31/v3 §5.4：同一交易命中多个预警时复用解释引用、去重计量——共享命中
                // 本身不是重复用款，不阻断 EXPLAINED；登记背景问题供人工核对去重口径
                //（额度使用按唯一交易计一次，档案按 sourceRecordId 去重统计）。
                ensureIssue(caseEntity.getId(), unit.getId(),
                        "AUTHORITY_SHARED_REFERENCE:" + unit.getId(), IssueSeverity.CONTEXT_GAP, "Q4",
                        "交易 " + String.join("、", overlaps) + " 同时出现在其他单元的授权覆盖声明中；"
                                + "若为同一笔资金的共享命中，按唯一交易去重计量（不重复消耗额度）；"
                                + "若为不同资金，需更正声明。请人工核对后处置该问题。",
                        actor);
            }
        }
        JsonNode c3 = claims.get("C3");
        JsonNode c4 = claims.get("C4");
        if (c3 != null && c3.isObject()) {
            String status = text(c3, "status", "UNASSESSED");
            if ("CONTRADICTED".equals(status) || "UNRESOLVED".equals(status) || "UNASSESSED".equals(status)) {
                ensureIssue(caseEntity.getId(), unit.getId(),
                        "GROUP_AUTHORITY:" + unit.getId(), IssueSeverity.DECISION_CRITICAL, "Q3",
                        "代付授权事实（C3）状态为 " + status + "；不能整体解释成立，"
                                + "需对授权签发、范围与撤销状态定向核验。", actor);
            }
        }
        if (c4 != null && c4.isObject()) {
            String status = text(c4, "status", "UNASSESSED");
            if ("CONTRADICTED".equals(status) || "UNRESOLVED".equals(status) || "UNASSESSED".equals(status)) {
                ensureIssue(caseEntity.getId(), unit.getId(),
                        "GROUP_EXECUTION:" + unit.getId(), IssueSeverity.DECISION_CRITICAL, "Q4",
                        "逐笔执行事实（C4）状态为 " + status + "；存在未被授权覆盖或无法解释的具体交易，"
                                + "需逐笔核对分配。", actor);
            }
        }
        // 授权额度缺口：声明授权额度 < 命中交易合计 → 缺口保留（TP-10）。
        JsonNode authority = draft.get("authority");
        if (authority != null && authority.isObject()) {
            String limitText = text(authority, "limitAmount");
            if (!limitText.isBlank()) {
                try {
                    java.math.BigDecimal limit = new java.math.BigDecimal(limitText);
                    java.math.BigDecimal covered = java.math.BigDecimal.ZERO;
                    JsonNode coveredTxs = authority.get("coveredTransactionIds");
                    if (coveredTxs != null && coveredTxs.isArray()) {
                        Map<String, java.math.BigDecimal> sourceAmounts =
                                serverTransactionAmounts(caseEntity);
                        for (JsonNode tx : coveredTxs) {
                            java.math.BigDecimal amount = sourceAmounts.get(tx.asText());
                            if (amount != null) {
                                covered = covered.add(amount);
                            }
                        }
                    }
                    java.math.BigDecimal gap = limit.subtract(covered);
                    if (gap.compareTo(java.math.BigDecimal.ZERO) < 0) {
                        ensureIssue(caseEntity.getId(), unit.getId(),
                                "AUTHORITY_LIMIT_EXCEEDED:" + unit.getId(), IssueSeverity.DECISION_CRITICAL,
                                "Q4", "代付授权额度 " + limit.toPlainString()
                                        + " 低于已覆盖交易合计 " + covered.toPlainString()
                                        + "；超出部分 " + gap.negate().toPlainString()
                                        + " 保留缺口，不得整笔解释成立。", actor);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("授权额度需为定点数字符串：" + limitText);
                }
            }
        }
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

        // criticalUnknown 提前声明：范围未知（G1-1）与六问题（§6.1）都会设置
        boolean criticalUnknown = false;
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

        // 服务器冻结来源对账（A5-01/TP-04）：声明的命中交易必须存在于服务器可枚举的交易源集合，
        // 且金额与权威来源一致；自编交易 ID / 金额自洽不能通过提交。
        Map<String, java.math.BigDecimal> sourceAmounts = serverTransactionAmounts(caseEntity);
        if (sourceAmounts.isEmpty()) {
            throw new IllegalStateException("服务器无法枚举本客户的交易事实（来源数据缺失）；"
                    + "不能以调用方自证的交易集合形成解释依据，需先修复来源数据同步");
        }
        for (String tx : uniqueReviewed) {
            java.math.BigDecimal sourceAmount = sourceAmounts.get(tx);
            if (sourceAmount == null) {
                throw new IllegalArgumentException("命中交易 " + tx + " 不属于服务器冻结的交易来源集合；"
                        + "自编交易 ID 不能作为解释依据");
            }
            java.math.BigDecimal declared2 = new java.math.BigDecimal(amountByTx.get(tx));
            if (declared2.compareTo(sourceAmount) != 0) {
                throw new IllegalArgumentException("交易 " + tx + " 的声明金额（" + declared2.toPlainString()
                        + "）与服务器来源金额（" + sourceAmount.toPlainString() + "）不一致；"
                        + "以来源金额为准，费用/退款用独立调整项表达");
            }
        }

        // G1-1/RF-05：服务器冻结的预警命中范围缺口校验——
        // 客户端 reviewedTransactionIds 只表达调查进度；冻结全集中的命中交易必须被覆盖。
        List<String> scopeGaps = alertScopeService.scopeGaps(unit.getAlertId(), uniqueReviewed);
        if (!scopeGaps.isEmpty() && scopeGaps.get(0).startsWith("预警 ") && scopeGaps.size() == 1
                && scopeGaps.get(0).contains("尚未由服务器冻结")) {
            // 范围未知（未冻结）：保留问题路径——登记 OPEN 范围缺口问题，阻断 EXPLAINED 但允许 UNRESOLVED
            ensureIssue(caseEntity.getId(), unit.getId(), "SCOPE_UNFROZEN:" + unit.getId(),
                    IssueSeverity.DECISION_CRITICAL, null,
                    "预警命中范围尚未由服务器冻结（RF-05）：需先枚举并冻结命中交易全集，"
                            + "调查进度不能定义预警全集。", "system");
            if (outcome == ExplanationOutcome.EXPLAINED) {
                throw new IllegalArgumentException("预警 " + unit.getAlertId()
                        + " 的命中范围尚未冻结，不能建议 EXPLAINED；请先完成范围冻结（RF-05）");
            }
            criticalUnknown = true;
        } else if (!scopeGaps.isEmpty()) {
            // 冻结后仍有缺口：客户端删去命中交易不能隐去（RF-05 主断言）
            throw new IllegalArgumentException("命中范围缺口：服务器冻结的命中交易未被解释范围覆盖："
                    + String.join("、", scopeGaps) + "；删去命中交易不能隐去缺口（RF-05），"
                    + "需补充对应交易或申请来源更正");
        }

        // 六问题
        JsonNode questions = draft.get("questions");
        if (questions == null || !questions.isObject()) {
            throw new IllegalArgumentException("草稿缺少六问题（questions）");
        }
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
                // FR-02/RF-02：NOT_APPLICABLE 是"服务端核定通过的例外"，不是用户可自由勾选的跳过；
                // 政策未允许该题例外时，整体不适用必须拒绝（"没有证据解释"≠"已有证据证实例外"）。
                if (!policyCatalog.notApplicableAllowed(policyCode, code)) {
                    throw new IllegalArgumentException("问题 " + code + " 在配方 " + policyCode
                            + " 中是核心问题，不允许整体不适用（FR-02）；请补充核验或提交 UNRESOLVED 保留未知");
                }
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
            List<Long> questionArtifacts = new ArrayList<>();
            for (JsonNode artifactId : intList(question, "artifactVersionIds")) {
                long id = artifactId.asLong();
                EvidenceArtifactVersion artifact = artifactRepository.findByIdAndCaseId(id, caseEntity.getId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "问题 " + code + " 引用的材料不属于当前案件：" + id));
                artifactIds.add(id);
                questionArtifacts.add(id);
            }
            for (JsonNode issueId : intList(question, "issueIds")) {
                long id = issueId.asLong();
                ExplanationIssue issue = issueRepository.findByIdAndCaseId(id, caseEntity.getId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "问题 " + code + " 引用的问题不属于当前案件：" + id));
                issueIds.add(id);
            }
            // FR-01（v4 §4.1）：可采用的结论（SATISFIED/NOT_SATISFIED）逐题独立评估——
            // 由统一评估器核验"RESOLVED 材料 + 本题事实上的有效 CONFIRMED 核验链"；
            // 不共享其它题的累积材料集合（Q1 的核验不能替 Q2 通过），同一材料不同事实
            // 的核验链互不错误覆盖；UNRESOLVED/MISMATCH 使该题失去肯定支持。
            if (assessment == QuestionAssessment.SATISFIED || assessment == QuestionAssessment.NOT_SATISFIED) {
                EvidenceAdmissibilityService.AdmissibilityResult verdict =
                        admissibilityService.assessQuestion(caseEntity.getId(),
                                new EvidenceAdmissibilityService.SubjectEvidence(code, questionArtifacts));
                if (!verdict.admissible()) {
                    throw new IllegalArgumentException(verdict.remediation());
                }
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
        // EXPLAINED 约束：全部问题已回答（SATISFIED/NOT_APPLICABLE）且无关键未知（A5-02：逐题可采用条件）
        if (outcome == ExplanationOutcome.EXPLAINED) {
            if (criticalUnknown || openCritical) {
                throw new IllegalArgumentException("存在关键未知或未解决的关键问题，不能建议 EXPLAINED；"
                        + "可选择 SUSPICIOUS（记录怀疑依据）或 UNRESOLVED（保留未知）");
            }
            // 逐题可采用条件：NOT_SATISFIED 表示该问题的核验未通过；解释成立要求全部问题
            // SATISFIED 或具有明确理由与适用依据的 NOT_APPLICABLE（A5-02）。
            for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
                JsonNode question = questions.get(code);
                QuestionAssessment assessment = parseEnum(QuestionAssessment.class,
                        text(question, "assessment"), "问题 " + code + " 评估");
                if (assessment == QuestionAssessment.NOT_SATISFIED) {
                    throw new IllegalArgumentException("问题 " + code + " 的核验结论为 NOT_SATISFIED，"
                            + "不能建议 EXPLAINED；请先解决该问题（补充核验或修订判断），"
                            + "或提交 UNRESOLVED 保留未知（NOT_SATISFIED 不会自动变成 SUSPICIOUS）");
                }
            }
            // 代付配方（v3 计划 §6/§8）：C1~C4 必须全部 SUPPORTED；任何 CONTRADICTED/UNRESOLVED
            // 阻断 EXPLAINED（TP-06/TP-09/TP-12）。部分授权（TP-10/TP-11）在金额/订单缺口中阻断。
            if (ExplanationPolicyCatalog.GOODS_GROUP_PAYMENT_V1.equals(policyCode)) {
                validateGroupPaymentClaims(caseEntity, draft);
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
                unit.getHypothesisId(), unit.getPolicyCode(), unit.getDraftRevision(), unit.getDraftJson(),
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

    private ExplanationViews.IssueReviewView issueReviewView(ExplanationIssueReview review) {
        return new ExplanationViews.IssueReviewView(review.getId(), review.getCaseId(), review.getIssueId(),
                review.getProposalRevision(), review.getOriginalSeverity(), review.getProposedSeverity(),
                review.getReason(), review.getEvidenceReference(), review.getProposedBy(),
                String.valueOf(review.getProposedAt()), review.getStatus(), review.getConfirmedBy(),
                review.getConfirmedAt() == null ? null : String.valueOf(review.getConfirmedAt()),
                review.getRejectedReason());
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
                text(policy, "deliveryDueDate"),
                text(policy, "groupRelationshipStatus"));
    }

    /**
     * 稳定请求摘要（v3 计划 §9.2 / TP-27 修复）：
     * 只绑定幂等重放语义所需的不变内容——案件、单元、政策与草稿规范化 JSON。
     * 不混入 caseFactsEpoch：成功提交会推进 epoch，同 requestId 重放必须返回原结果；
     * epoch/令牌的并发保护由 reviewBasisToken 负责（二者职责分离，不叠加）。
     */
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
        return sha256Hex(payload.toString());
    }

    private long linkedAlertCount(Long caseId) {
        return alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(alert -> alert.getStatus() == com.bank.aml.investigation.AlertStatus.LINKED)
                .count();
    }

    /**
     * 服务器冻结的交易来源集合（v3 计划 §9.1 / A5-01）：
     * 以 CustomerDataPort 的 sourceRecordId 为键返回权威金额；来源身份缺失的交易不进入对账
     * （显式未知优先于编造身份）。范围对账、授权额度与档案口径统一使用本集合。
     */
    private Map<String, java.math.BigDecimal> serverTransactionAmounts(CaseEntity caseEntity) {
        Map<String, java.math.BigDecimal> amounts = new java.util.LinkedHashMap<>();
        try {
            for (com.bank.aml.domain.TransactionRecord transaction : customerDataPort
                    .transactionsOf(caseEntity.getCustomerId())) {
                if (transaction.sourceRecordId() == null || transaction.sourceRecordId().isBlank()) {
                    continue;
                }
                amounts.putIfAbsent(transaction.sourceRecordId(), transaction.amount());
            }
        } catch (RuntimeException e) {
            // 来源读取失败：返回空集合 → 调用方按"来源数据缺失"阻断，不静默降级为可解释。
            return Map.of();
        }
        return amounts;
    }

    /** 同事务持久化材料/问题使用记录（A5-05）：来源变更按本表反查受影响提交。 */
    private void persistEvidenceUses(Long caseId, Long submissionId, JsonNode draft, DraftSummary summary) {
        JsonNode questions = draft.get("questions");
        boolean wrote = false;
        if (questions != null && questions.isObject()) {
            for (String code : QUESTION_CODES) {
                JsonNode question = questions.get(code);
                if (question == null || !question.isObject()) {
                    continue;
                }
                for (JsonNode artifactId : intList(question, "artifactVersionIds")) {
                    ExplanationEvidenceUse use = new ExplanationEvidenceUse();
                    use.setSubmissionId(submissionId);
                    use.setCaseId(caseId);
                    use.setQuestionCode(code);
                    use.setArtifactVersionId(artifactId.asLong());
                    use.setDirection(ExplanationEvidenceDirection.SUPPORTS_EXPLANATION);
                    use.setLocation(text(question, "factLocation"));
                    evidenceUseRepository.save(use);
                    wrote = true;
                }
                for (JsonNode issueId : intList(question, "issueIds")) {
                    ExplanationEvidenceUse use = new ExplanationEvidenceUse();
                    use.setSubmissionId(submissionId);
                    use.setCaseId(caseId);
                    use.setQuestionCode(code);
                    use.setVerificationEventId(null);
                    use.setDirection(ExplanationEvidenceDirection.CHALLENGES_EXPLANATION);
                    use.setNote("issue:" + issueId.asLong());
                    evidenceUseRepository.save(use);
                    wrote = true;
                }
            }
        }
        if (!wrote) {
            // 无任何引用的提交显式留痕（依赖链完整可查询）。
            // A6-01：SATISFIED/NOT_SATISFIED 必须引用 RESOLVED 材料 + 核验记录，无引用的
            // EXPLAINED/SUSPICIOUS 已被六问题校验拒绝；本 CONTEXT 记录只可能出现在
            // UNRESOLVED 提交（保留未知调查现状），不会升级为可采用解释。
            ExplanationEvidenceUse use = new ExplanationEvidenceUse();
            use.setSubmissionId(submissionId);
            use.setCaseId(caseId);
            use.setQuestionCode("Q1");
            use.setDirection(ExplanationEvidenceDirection.CONTEXT);
            use.setNote("submission-without-referenced-evidence");
            evidenceUseRepository.save(use);
        }
    }

    /** 同事务冻结核验依据版本（A5-01）：服务器可枚举交易集 + 范围摘要（可重算）。 */
    private void persistVerificationBasis(CaseEntity caseEntity, ExplanationSubmission submission,
                                          JsonNode draft) {
        int nextRevision = basisRepository.findTopByCaseIdOrderByBasisRevisionDesc(caseEntity.getId())
                .map(VerificationBasis::getBasisRevision).orElse(0) + 1;
        Map<String, java.math.BigDecimal> sourceAmounts = serverTransactionAmounts(caseEntity);
        ObjectNode scopeJson = objectMapper.createObjectNode();
        ObjectNode transactions = scopeJson.putObject("serverTransactions");
        sourceAmounts.forEach((key, value) -> transactions.put(key, value.toPlainString()));
        JsonNode scope = draft.get("scope");
        List<String> reviewed = scope == null ? List.of() : stringList(scope, "reviewedTransactionIds");
        scopeJson.putPOJO("declaredReviewed", objectMapper.valueToTree(reviewed));
        scopeJson.put("submissionId", submission.getId());
        scopeJson.put("outcome", submission.getOutcome().name());
        String scopeJsonText = scopeJson.toString();
        String scopeDigest = sha256Hex(scopeJsonText);
        VerificationBasis basis = new VerificationBasis();
        basis.setCaseId(caseEntity.getId());
        basis.setBasisRevision(nextRevision);
        basis.setScopeJson(scopeJsonText);
        basis.setSourceCutoff(LocalDateTime.now(clock));
        basis.setScopeDigest(scopeDigest);
        basis.setBasisDigest(sha256Hex(scopeJsonText + "|" + submission.getInputDigest()));
        basis.setCreatedBy(submission.getSubmittedBy());
        basis.setCreatedAt(LocalDateTime.now(clock));
        basisRepository.save(basis);
        submission.setBasisId(basis.getId());
        submissionRepository.save(submission);
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
