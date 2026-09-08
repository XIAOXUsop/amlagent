package com.bank.aml.investigation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.review.ReviewDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 调查假设、证据和逐预警覆盖结论的业务一致性边界。 */
@Service
public class InvestigationService {
    private static final Pattern EVIDENCE_REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}");

    private final CaseRepository caseRepository;
    private final AmlAlertRepository alertRepository;
    private final InvestigationHypothesisRepository hypothesisRepository;
    private final InvestigationEvidenceLinkRepository evidenceRepository;
    private final AlertInvestigationCoverageRepository coverageRepository;
    private final InvestigationPlaybookCatalog playbooks;
    private final InvestigationReadinessEvaluator readinessEvaluator;
    private final AuditOutboxService auditOutbox;
    /** v2 解释核验就绪端口；null 仅允许出现在旧单测构造中（生产装配必有）。 */
    private final com.bank.aml.explanation.ExplanationReadinessPort explanationReadiness;

    public InvestigationService(CaseRepository caseRepository,
                                AmlAlertRepository alertRepository,
                                InvestigationHypothesisRepository hypothesisRepository,
                                InvestigationEvidenceLinkRepository evidenceRepository,
                                AlertInvestigationCoverageRepository coverageRepository,
                                InvestigationPlaybookCatalog playbooks,
                                InvestigationReadinessEvaluator readinessEvaluator,
                                AuditOutboxService auditOutbox) {
        this(caseRepository, alertRepository, hypothesisRepository, evidenceRepository, coverageRepository,
                playbooks, readinessEvaluator, auditOutbox, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InvestigationService(CaseRepository caseRepository,
                                AmlAlertRepository alertRepository,
                                InvestigationHypothesisRepository hypothesisRepository,
                                InvestigationEvidenceLinkRepository evidenceRepository,
                                AlertInvestigationCoverageRepository coverageRepository,
                                InvestigationPlaybookCatalog playbooks,
                                InvestigationReadinessEvaluator readinessEvaluator,
                                AuditOutboxService auditOutbox,
                                com.bank.aml.explanation.ExplanationReadinessPort explanationReadiness) {
        this.caseRepository = caseRepository;
        this.alertRepository = alertRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.evidenceRepository = evidenceRepository;
        this.coverageRepository = coverageRepository;
        this.playbooks = playbooks;
        this.readinessEvaluator = readinessEvaluator;
        this.auditOutbox = auditOutbox;
        this.explanationReadiness = explanationReadiness;
    }

    /** 与预警归并事务共同调用，按场景幂等创建默认调查假设和覆盖项。 */
    public InvestigationHypothesis initializeForAlert(Long caseId, AmlAlert alert, String operator) {
        InvestigationPlaybookCatalog.Playbook playbook = playbooks.require(alert.getScenarioCode());
        InvestigationHypothesis hypothesis = hypothesisRepository
                .findByCaseIdAndHypothesisCode(caseId, playbook.defaultHypothesisCode())
                .orElseGet(() -> {
                    InvestigationHypothesis created = new InvestigationHypothesis();
                    created.setCaseId(caseId);
                    created.setScenarioCode(playbook.code());
                    created.setHypothesisCode(playbook.defaultHypothesisCode());
                    created.setTitle(playbook.defaultHypothesisTitle());
                    created.setInvestigationQuestion(playbook.investigationQuestion());
                    created.setRequiredEvidenceTypes(playbook.requiredEvidenceTypes().stream()
                            .map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
                    created.setStatus(HypothesisStatus.OPEN);
                    created.setCreatedBy(operator);
                    return hypothesisRepository.save(created);
                });
        coverageRepository.findByAlertId(alert.getId()).orElseGet(() -> {
            AlertInvestigationCoverage coverage = new AlertInvestigationCoverage();
            coverage.setAlertId(alert.getId());
            coverage.setCaseId(caseId);
            coverage.setHypothesisId(hypothesis.getId());
            coverage.setConclusion(AlertCoverageConclusion.PENDING);
            return coverageRepository.save(coverage);
        });
        return hypothesis;
    }

    @Transactional(readOnly = true)
    public CaseInvestigationView get(Long caseId) {
        CaseEntity caseEntity = requireCase(caseId);
        List<AlertView> alertViews = alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .map(AlertView::from).toList();
        List<AmlAlert> linkedAlerts = alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(alert -> alert.getStatus() == AlertStatus.LINKED).toList();
        List<InvestigationHypothesis> hypotheses = hypothesisRepository.findByCaseIdOrderByIdAsc(caseId);
        List<AlertInvestigationCoverage> coverage = coverageRepository.findByCaseIdOrderByAlertIdAsc(caseId);
        InvestigationReadinessEvaluator.Result result;
        if (caseEntity.getInvestigationContractVersion() >= 3) {
            result = new InvestigationReadinessEvaluator.Result(false,
                    List.of("调查契约版本 " + caseEntity.getInvestigationContractVersion() + " 不受支持，拒绝写入与最终处置"),
                    List.of("调查契约版本不受支持"), List.of("调查契约版本不受支持"));
        } else if (caseEntity.getInvestigationContractVersion() == 2) {
            // v2：混合结论按解释核验决策表执行，不走 v1 假设映射（§7.3）
            result = explanationReadiness != null
                    ? explanationReadiness.readinessForCase(caseId)
                    : new InvestigationReadinessEvaluator.Result(false,
                        List.of("解释核验工作区未初始化"), List.of("解释核验工作区未初始化"),
                        List.of("解释核验工作区未初始化"));
        } else {
            InvestigationReadinessEvaluator.Facts facts = loadFacts(caseEntity, linkedAlerts, hypotheses, coverage);
            result = readinessEvaluator.evaluate(facts);
        }
        return new CaseInvestigationView(caseEntity.getInvestigationContractVersion(),
                alertViews,
                hypotheses.stream().map(this::hypothesisView).toList(),
                coverage.stream().map(AlertCoverageView::from).toList(),
                result.readyForFinalReview(),
                result.generalBlockers(),
                result.confirmBlockers(),
                result.excludeBlockers());
    }

    @Transactional
    public InvestigationEvidenceView addEvidence(Long caseId, Long hypothesisId,
                                                 String evidenceType, String evidenceReference,
                                                 String stance, String findingSummary, String operator) {
        CaseEntity caseEntity = lockEditableCase(caseId);
        guardContractWrite(caseEntity);
        InvestigationHypothesis hypothesis = hypothesisRepository
                .findByIdAndCaseIdForUpdate(hypothesisId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("调查假设不存在"));
        InvestigationEvidenceType type = parseEnum(InvestigationEvidenceType.class, evidenceType, "证据类型");
        EvidenceStance normalizedStance = parseEnum(EvidenceStance.class, stance, "证据方向");
        String reference = normalize(evidenceReference, 3, 160, "证据引用");
        if (!EVIDENCE_REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("证据引用只能包含字母、数字及 . _ : / -");
        }
        String summary = normalize(findingSummary, 10, 1000, "证据调查摘要");
        InvestigationEvidenceLink evidence = new InvestigationEvidenceLink();
        evidence.setCaseId(caseEntity.getId());
        evidence.setHypothesisId(hypothesis.getId());
        evidence.setEvidenceType(type);
        evidence.setEvidenceReference(reference);
        evidence.setStance(normalizedStance);
        evidence.setFindingSummary(summary);
        evidence.setCreatedBy(operator);
        InvestigationEvidenceLink saved = evidenceRepository.save(evidence);
        auditOutbox.enqueue("INVESTIGATION_EVIDENCE:" + caseId + ":" + hypothesisId + ":" + reference,
                operator, "INVESTIGATION_EVIDENCE_ADD", "CASE", String.valueOf(caseId),
                "hypothesisId=" + hypothesisId + ",type=" + type + ",stance=" + normalizedStance);
        return InvestigationEvidenceView.from(saved);
    }

    /**
     * 确认或排除调查假设。
     * <p>与既有请求完全相同（状态与依据均未变化）时幂等返回当前视图，不递增版本；
     * 实际修改了判断依据时递增版本，并在同一事务内把引用该假设的已决覆盖重置为 PENDING：
     * 旧结论引用的假设版本已经变化，不能被最终复核继续采用。
     */
    @Transactional
    public InvestigationHypothesisView updateHypothesis(Long caseId, Long hypothesisId, int expectedRevision,
                                                        String status, String rationale, String operator) {
        CaseEntity lockedCase = lockEditableCase(caseId);
        guardContractWrite(lockedCase);
        InvestigationHypothesis hypothesis = hypothesisRepository
                .findByIdAndCaseIdForUpdate(hypothesisId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("调查假设不存在"));
        if (hypothesis.getRevision() != expectedRevision) {
            // 统一版本冲突协议（409）：假设已被他人改判/更新，客户端必须刷新后重新确认。
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_HYPOTHESIS, hypothesisId,
                    hypothesis.getRevision(),
                    "调查假设版本已变化（当前版本 " + hypothesis.getRevision()
                            + "，请求基于版本 " + expectedRevision + "），请刷新后基于最新依据重新确认");
        }
        HypothesisStatus normalizedStatus = parseEnum(HypothesisStatus.class, status, "假设状态");
        if (normalizedStatus == HypothesisStatus.OPEN) {
            throw new IllegalArgumentException("更新操作必须确认或排除调查假设");
        }
        String normalizedRationale = normalize(rationale, 10, 2000, "假设判断依据");
        if (hypothesis.getStatus() == normalizedStatus && normalizedRationale.equals(hypothesis.getRationale())) {
            // 幂等重放：判断依据完全相同，不递增版本，也不重复使覆盖失效。
            return hypothesisView(hypothesis);
        }
        List<InvestigationEvidenceLink> evidence =
                evidenceRepository.findByHypothesisIdOrderByCreatedAtAsc(hypothesisId);
        Set<InvestigationEvidenceType> evidenceTypes = evidence.stream()
                .map(InvestigationEvidenceLink::getEvidenceType).collect(java.util.stream.Collectors.toSet());
        List<InvestigationEvidenceType> missing = requiredEvidenceTypes(hypothesis).stream()
                .filter(type -> !evidenceTypes.contains(type)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("调查假设缺少必需证据类型：" + missing);
        }
        EvidenceStance requiredStance = normalizedStatus == HypothesisStatus.CONFIRMED
                ? EvidenceStance.SUPPORTS : EvidenceStance.CONTRADICTS;
        if (evidence.stream().noneMatch(item -> item.getStance() == requiredStance)) {
            throw new IllegalStateException(normalizedStatus == HypothesisStatus.CONFIRMED
                    ? "确认假设至少需要一项支持证据" : "排除假设至少需要一项反向证据");
        }
        HypothesisStatus previousStatus = hypothesis.getStatus();
        int previousRevision = hypothesis.getRevision();
        hypothesis.setStatus(normalizedStatus);
        hypothesis.setRationale(normalizedRationale);
        hypothesis.setUpdatedBy(operator);
        hypothesis.setRevision(hypothesis.getRevision() + 1);
        InvestigationHypothesis saved = hypothesisRepository.save(hypothesis);
        // 同状态但依据改变同样使覆盖失效：覆盖引用的假设版本已经变化。
        List<Long> invalidated = invalidateCoveragesForHypothesis(caseId, hypothesisId, operator);
        auditOutbox.enqueue("INVESTIGATION_HYPOTHESIS:" + hypothesisId + ":v" + saved.getRevision(),
                operator, "INVESTIGATION_HYPOTHESIS_DECIDE", "CASE", String.valueOf(caseId),
                "hypothesisId=" + hypothesisId + ",status=" + previousStatus + "->" + normalizedStatus
                        + ",revision=" + previousRevision + "->" + saved.getRevision()
                        + ",rationaleLen=" + normalizedRationale.length()
                        + ",invalidatedCoverageIds=" + invalidated + ",invalidatedCoverageCount="
                        + invalidated.size());
        return hypothesisView(saved);
    }

    /**
     * 更新预警覆盖结论。
     * <p>覆盖与假设版本分别校验：{@code expectedRevision} 校验覆盖自身的乐观锁，
     * {@code expectedHypothesisRevision} 校验判断依据（假设）的版本。缺省或过期的假设版本会得到
     * 明确错误，不会默认采用当前版本而掩盖陈旧判断。保存成功时写入服务端锁定的当前假设版本。
     */
    @Transactional
    public AlertCoverageView updateCoverage(Long caseId, Long alertId, int expectedRevision,
                                            Long hypothesisId, Long expectedHypothesisRevision,
                                            String conclusion, String analysisSummary, String operator) {
        if (expectedHypothesisRevision == null) {
            throw new IllegalArgumentException("请提供覆盖所关联假设的当前版本（expectedHypothesisRevision）");
        }
        CaseEntity lockedCase = lockEditableCase(caseId);
        guardContractWrite(lockedCase);
        AmlAlert alert = alertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if (!caseId.equals(alert.getCaseId()) || alert.getStatus() != AlertStatus.LINKED) {
            throw new IllegalStateException("预警不属于当前案件");
        }
        AlertInvestigationCoverage coverage = coverageRepository
                .findByAlertIdAndCaseIdForUpdate(alertId, caseId)
                .orElseThrow(() -> new IllegalStateException("预警调查覆盖项不存在"));
        if (coverage.getRevision() != expectedRevision) {
            // 统一版本冲突协议（409）：覆盖已被他人更新；与“假设改判”用冲突类型区分，不混用 412。
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_COVERAGE, alertId,
                    coverage.getRevision(),
                    "预警覆盖结论版本已变化（当前版本 " + coverage.getRevision()
                            + "，请求基于版本 " + expectedRevision + "），请刷新后重新确认");
        }
        AlertCoverageConclusion normalizedConclusion =
                parseEnum(AlertCoverageConclusion.class, conclusion, "预警覆盖结论");
        if (normalizedConclusion == AlertCoverageConclusion.PENDING) {
            throw new IllegalArgumentException("请给出确认可疑或合理解释结论");
        }
        String summary = normalize(analysisSummary, 10, 1000, "预警覆盖分析");
        // 案件行锁内锁定假设：避免与假设改判交错，锁顺序与 updateHypothesis 一致（案件 → 假设）。
        InvestigationHypothesis hypothesis = hypothesisRepository
                .findByIdAndCaseIdForUpdate(hypothesisId, caseId)
                .orElseThrow(() -> new IllegalArgumentException("关联调查假设不存在"));
        if (hypothesis.getRevision() != expectedHypothesisRevision) {
            // 统一版本冲突协议（409）：判断依据（假设）已被改判，不得默认采用当前版本掩盖陈旧判断。
            throw new InvestigationRevisionConflictException(
                    InvestigationRevisionConflictException.TYPE_HYPOTHESIS, hypothesisId,
                    hypothesis.getRevision(),
                    "判断依据的假设已改判（当前版本 " + hypothesis.getRevision()
                            + "，请求基于版本 " + expectedHypothesisRevision + "），请刷新后基于最新依据重新确认");
        }
        if (normalizedConclusion == AlertCoverageConclusion.SUSPICIOUS
                && hypothesis.getStatus() != HypothesisStatus.CONFIRMED) {
            throw new IllegalStateException("确认可疑的预警必须关联已确认假设");
        }
        if (normalizedConclusion == AlertCoverageConclusion.EXPLAINED
                && hypothesis.getStatus() != HypothesisStatus.REJECTED) {
            throw new IllegalStateException("合理解释的预警必须关联已排除假设");
        }
        coverage.setHypothesisId(hypothesisId);
        // 服务端写入锁定的当前假设版本，不信任客户端回传值作为事实。
        coverage.setHypothesisRevision((long) hypothesis.getRevision());
        coverage.setConclusion(normalizedConclusion);
        coverage.setAnalysisSummary(summary);
        coverage.setUpdatedBy(operator);
        coverage.setRevision(coverage.getRevision() + 1);
        AlertInvestigationCoverage saved = coverageRepository.save(coverage);
        auditOutbox.enqueue("ALERT_COVERAGE:" + alertId + ":" + expectedRevision,
                operator, "ALERT_COVERAGE_DECIDE", "ALERT", String.valueOf(alertId),
                "caseId=" + caseId + ",hypothesisId=" + hypothesisId
                        + ",hypothesisRevision=" + hypothesis.getRevision()
                        + ",conclusion=" + normalizedConclusion);
        return AlertCoverageView.from(saved);
    }

    /** 契约版本白名单（§7.3）：>=3 未知版本拒绝写入；==2 由解释核验单元提交入口接管。 */
    private void guardContractWrite(CaseEntity caseEntity) {
        int version = caseEntity.getInvestigationContractVersion();
        if (version >= 3) {
            throw new IllegalArgumentException("调查契约版本 " + version + " 不受支持，拒绝写入");
        }
        if (version == 2) {
            throw new IllegalStateException("该案件启用了合理解释核验政策（v2）："
                    + "请通过预警单元提交入口更新调查结论，旧调查写入接口已关闭");
        }
    }

    /** 由人工最终处置事务调用；补充尽调不是最终结论，因此不阻断。 */
    public void validateReadyForReview(CaseEntity caseEntity, ReviewDecision decision) {
        int version = caseEntity.getInvestigationContractVersion();
        if (version >= 3) {
            throw new IllegalStateException("调查契约版本 " + version + " 不受支持，拒绝最终处置");
        }
        if (version == 2) {
            throw new IllegalStateException("v2 案件的最终处置由解释核验服务校验（决策表 + 依据令牌 + 自审限制）");
        }
        if (version < 1
                || decision == ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE) return;
        List<AmlAlert> alerts = alertRepository.findByCaseIdOrderByOccurredAtAsc(caseEntity.getId()).stream()
                .filter(alert -> alert.getStatus() == AlertStatus.LINKED).toList();
        List<InvestigationHypothesis> hypotheses =
                hypothesisRepository.findByCaseIdOrderByIdAsc(caseEntity.getId());
        List<AlertInvestigationCoverage> coverage =
                coverageRepository.findByCaseIdOrderByAlertIdAsc(caseEntity.getId());
        InvestigationReadinessEvaluator.Facts facts =
                new InvestigationReadinessEvaluator.Facts(caseEntity.getInvestigationContractVersion(),
                        alerts, hypotheses, coverage, evidenceByHypothesis(caseEntity.getId()));
        List<String> blockers = readinessEvaluator.blockersFor(facts, decision);
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("调查尚未满足最终处置条件：" + String.join("；", blockers));
        }
    }

    /** 案件当前调查事实（不含决策结论），供运营队列分流与前端展示复用。 */
    public InvestigationReadinessEvaluator.Facts loadFactsForCase(Long caseId) {
        List<AmlAlert> alerts = alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(alert -> alert.getStatus() == AlertStatus.LINKED).toList();
        List<InvestigationHypothesis> hypotheses = hypothesisRepository.findByCaseIdOrderByIdAsc(caseId);
        List<AlertInvestigationCoverage> coverage = coverageRepository.findByCaseIdOrderByAlertIdAsc(caseId);
        return loadFacts(requireCase(caseId), alerts, hypotheses, coverage);
    }

    /** 拆分预警时把覆盖项移到新案件并重置结论，避免沿用原案件判断。 */
    public void resetCoverageForSplit(Long alertId, Long newCaseId, Long newHypothesisId) {
        AlertInvestigationCoverage coverage = coverageRepository.findByAlertId(alertId)
                .orElseThrow(() -> new IllegalStateException("预警调查覆盖项不存在"));
        coverage.setCaseId(newCaseId);
        coverage.setHypothesisId(newHypothesisId);
        coverage.setHypothesisRevision(null);
        coverage.setConclusion(AlertCoverageConclusion.PENDING);
        coverage.setAnalysisSummary(null);
        coverage.setUpdatedBy(null);
        coverage.setRevision(coverage.getRevision() + 1);
        coverageRepository.save(coverage);
    }

    /**
     * 只有调查事实尚未形成时才允许重新划分案件边界。否则无法证明已有证据究竟属于
     * 被拆出的预警还是仍留在原案件中的其他预警。
     */
    public void validateCanSplit(Long sourceCaseId) {
        if (evidenceRepository.existsByCaseId(sourceCaseId)) {
            throw new IllegalStateException("案件已录入调查证据，不能再拆分预警");
        }
        if (hypothesisRepository.findByCaseIdOrderByIdAsc(sourceCaseId).stream()
                .anyMatch(item -> item.getStatus() != HypothesisStatus.OPEN)) {
            throw new IllegalStateException("案件已有已决调查假设，不能再拆分预警");
        }
        if (coverageRepository.findByCaseIdOrderByAlertIdAsc(sourceCaseId).stream()
                .anyMatch(item -> item.getConclusion() != AlertCoverageConclusion.PENDING)) {
            throw new IllegalStateException("案件已有预警覆盖结论，不能再拆分预警");
        }
    }

    /** 拆分后删除原案件中已无预警引用的空白默认假设。 */
    public void removeUnusedOpenHypotheses(Long sourceCaseId) {
        Set<Long> referenced = coverageRepository.findByCaseIdOrderByAlertIdAsc(sourceCaseId).stream()
                .map(AlertInvestigationCoverage::getHypothesisId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        hypothesisRepository.findByCaseIdOrderByIdAsc(sourceCaseId).stream()
                .filter(item -> item.getStatus() == HypothesisStatus.OPEN)
                .filter(item -> !referenced.contains(item.getId()))
                .filter(item -> evidenceRepository.findByHypothesisIdOrderByCreatedAtAsc(item.getId()).isEmpty())
                .forEach(hypothesisRepository::delete);
    }

    /**
     * 假设改判的覆盖失效处理：把当前案件中引用该假设的所有已决覆盖重置为 PENDING，
     * 清除假设版本绑定并递增覆盖版本。原分析文字保留作为待重新确认的参考（前端显式标注已失效）。
     * 必须在已锁案件行、已锁定假设的同一事务内调用；审计登记失败时与假设修改共同回滚。
     */
    private List<Long> invalidateCoveragesForHypothesis(Long caseId, Long hypothesisId, String operator) {
        List<Long> invalidated = new ArrayList<>();
        for (AlertInvestigationCoverage item : coverageRepository.findByCaseIdOrderByAlertIdAsc(caseId)) {
            if (!hypothesisId.equals(item.getHypothesisId())) {
                continue;
            }
            invalidated.add(item.getAlertId());
            item.setConclusion(AlertCoverageConclusion.PENDING);
            item.setHypothesisRevision(null);
            item.setUpdatedBy(operator);
            item.setRevision(item.getRevision() + 1);
            coverageRepository.save(item);
        }
        return invalidated;
    }

    private Map<Long, List<InvestigationEvidenceLink>> evidenceByHypothesis(Long caseId) {
        Map<Long, List<InvestigationEvidenceLink>> evidenceByHypothesis = new LinkedHashMap<>();
        for (InvestigationEvidenceLink link : evidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId)) {
            evidenceByHypothesis.computeIfAbsent(link.getHypothesisId(), key -> new ArrayList<>()).add(link);
        }
        return evidenceByHypothesis;
    }

    private InvestigationReadinessEvaluator.Facts loadFacts(CaseEntity caseEntity, List<AmlAlert> alerts,
                                                            List<InvestigationHypothesis> hypotheses,
                                                            List<AlertInvestigationCoverage> coverage) {
        return new InvestigationReadinessEvaluator.Facts(caseEntity.getInvestigationContractVersion(),
                alerts, hypotheses, coverage, evidenceByHypothesis(caseEntity.getId()));
    }

    private InvestigationHypothesisView hypothesisView(InvestigationHypothesis hypothesis) {
        return new InvestigationHypothesisView(hypothesis.getId(), hypothesis.getCaseId(),
                hypothesis.getScenarioCode(), hypothesis.getHypothesisCode(), hypothesis.getTitle(),
                hypothesis.getInvestigationQuestion(), requiredEvidenceTypes(hypothesis), hypothesis.getStatus(),
                hypothesis.getRationale(), hypothesis.getRevision(), hypothesis.getCreatedBy(),
                hypothesis.getUpdatedBy(), hypothesis.getCreatedAt(), hypothesis.getUpdatedAt(),
                evidenceRepository.findByHypothesisIdOrderByCreatedAtAsc(hypothesis.getId()).stream()
                        .map(InvestigationEvidenceView::from).toList());
    }

    private List<InvestigationEvidenceType> requiredEvidenceTypes(InvestigationHypothesis hypothesis) {
        if (hypothesis.getRequiredEvidenceTypes() == null || hypothesis.getRequiredEvidenceTypes().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(hypothesis.getRequiredEvidenceTypes().split(","))
                .map(value -> InvestigationEvidenceType.valueOf(value.trim())).toList();
    }

    private CaseEntity requireCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private CaseEntity lockEditableCase(Long caseId) {
        CaseEntity caseEntity = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        if (!Set.of(CaseStatus.PENDING, CaseStatus.HOLD).contains(caseEntity.getStatus())) {
            throw new IllegalStateException("仅待处理或人工复核中的案件可以更新调查记录");
        }
        return caseEntity;
    }

    private String normalize(String value, int min, int max, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + "需为 " + min + " ~ " + max + " 个有效字符");
        }
        return normalized;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + "不在允许范围内");
        }
    }
}
