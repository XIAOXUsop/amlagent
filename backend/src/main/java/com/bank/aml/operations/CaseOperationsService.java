package com.bank.aml.operations;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertInvestigationCoverage;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AlertStatus;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.HypothesisStatus;
import com.bank.aml.investigation.InvestigationHypothesis;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.investigation.InvestigationReadinessEvaluator;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.reporting.SuspiciousTransactionReport;
import com.bank.aml.reporting.SuspiciousTransactionReportRepository;
import com.bank.aml.reporting.SuspiciousTransactionReportStatus;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecutionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** P1 风险优先队列与分阶段 SLA 口径。 */
@Service
public class CaseOperationsService {
    private static final List<CaseStatus> ACTIVE_STATUSES = List.of(
            CaseStatus.PENDING, CaseStatus.RUNNING, CaseStatus.RETRY_WAIT,
            CaseStatus.HOLD, CaseStatus.REPORT_PENDING, CaseStatus.FAILED);

    private final CaseRepository caseRepository;
    private final AmlAlertRepository alertRepository;
    private final EnhancedDueDiligenceRequestRepository eddRepository;
    private final CaseExecutionRepository executionRepository;
    private final SuspiciousTransactionReportRepository reportRepository;
    private final InvestigationHypothesisRepository hypothesisRepository;
    private final AlertInvestigationCoverageRepository coverageRepository;
    private final com.bank.aml.investigation.InvestigationEvidenceLinkRepository investigationEvidenceRepository;
    private final InvestigationReadinessEvaluator readinessEvaluator;
    /** v2 解释核验就绪端口：contractVersion==2 的案件由决策表提供就绪结论（可为 null，仅存量测试）。 */
    private final com.bank.aml.explanation.ExplanationReadinessPort explanationReadiness;
    private final Clock clock;

    @Autowired
    public CaseOperationsService(CaseRepository caseRepository, AmlAlertRepository alertRepository,
                                 EnhancedDueDiligenceRequestRepository eddRepository,
                                 CaseExecutionRepository executionRepository,
                                 SuspiciousTransactionReportRepository reportRepository,
                                 InvestigationHypothesisRepository hypothesisRepository,
                                 AlertInvestigationCoverageRepository coverageRepository,
                                 com.bank.aml.investigation.InvestigationEvidenceLinkRepository investigationEvidenceRepository,
                                 InvestigationReadinessEvaluator readinessEvaluator,
                                 com.bank.aml.explanation.ExplanationReadinessPort explanationReadiness) {
        this(caseRepository, alertRepository, eddRepository, executionRepository, reportRepository,
                hypothesisRepository, coverageRepository, investigationEvidenceRepository, readinessEvaluator,
                explanationReadiness, Clock.systemDefaultZone());
    }

    /** 兼容既有构造（存量测试与旧调用）：使用默认就绪评估器，不注入调查仓库。 */
    CaseOperationsService(CaseRepository caseRepository, AmlAlertRepository alertRepository,
                          EnhancedDueDiligenceRequestRepository eddRepository,
                          CaseExecutionRepository executionRepository,
                          SuspiciousTransactionReportRepository reportRepository, Clock clock) {
        this(caseRepository, alertRepository, eddRepository, executionRepository, reportRepository,
                null, null, null, new InvestigationReadinessEvaluator(), null, clock);
    }

    CaseOperationsService(CaseRepository caseRepository, AmlAlertRepository alertRepository,
                          EnhancedDueDiligenceRequestRepository eddRepository,
                          CaseExecutionRepository executionRepository,
                          SuspiciousTransactionReportRepository reportRepository,
                          InvestigationHypothesisRepository hypothesisRepository,
                          AlertInvestigationCoverageRepository coverageRepository,
                          com.bank.aml.investigation.InvestigationEvidenceLinkRepository investigationEvidenceRepository,
                          InvestigationReadinessEvaluator readinessEvaluator,
                          com.bank.aml.explanation.ExplanationReadinessPort explanationReadiness, Clock clock) {
        this.caseRepository = caseRepository;
        this.alertRepository = alertRepository;
        this.eddRepository = eddRepository;
        this.executionRepository = executionRepository;
        this.reportRepository = reportRepository;
        this.hypothesisRepository = hypothesisRepository;
        this.coverageRepository = coverageRepository;
        this.investigationEvidenceRepository = investigationEvidenceRepository;
        this.readinessEvaluator = readinessEvaluator;
        this.explanationReadiness = explanationReadiness;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CaseOperationsView get(Long caseId) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        return view(caseEntity);
    }

    @Transactional(readOnly = true)
    public List<CaseOperationsView> queue(String username, String role, boolean overdueOnly,
                                          CasePriority priority, OperationPhase phase) {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        List<CaseEntity> active = caseRepository.findByStatusInOrderByCreatedAtAsc(ACTIVE_STATUSES);
        // 队列批量预加载调查就绪结论，避免逐条预警/逐案件重复查库。
        Map<Long, InvestigationReadinessEvaluator.Result> readiness = preloadReadiness(active);
        return active.stream()
                .map(item -> view(item, readiness.get(item.getId())))
                .filter(item -> visibleTo(item, username, normalizedRole))
                .filter(item -> !overdueOnly || item.overdue())
                .filter(item -> priority == null || item.priority() == priority)
                .filter(item -> phase == null || item.phase() == phase)
                .sorted(Comparator.comparing(CaseOperationsView::overdue).reversed()
                        .thenComparing(CaseOperationsView::priorityScore, Comparator.reverseOrder())
                        .thenComparing(CaseOperationsView::dueAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CaseOperationsView::caseId))
                .toList();
    }

    /** 为调查契约 v1/v2 的 HOLD 案件批量计算就绪结论（v2 走决策表端口）；其他案件不进入门禁。 */
    private Map<Long, InvestigationReadinessEvaluator.Result> preloadReadiness(Collection<CaseEntity> cases) {
        List<Long> v2CaseIds = cases.stream()
                .filter(item -> item.getStatus() == CaseStatus.HOLD
                        && item.getInvestigationContractVersion() == 2 && explanationReadiness != null)
                .map(CaseEntity::getId).toList();
        if (!v2CaseIds.isEmpty()) {
            Map<Long, InvestigationReadinessEvaluator.Result> v2Results = new HashMap<>();
            for (Long caseId : v2CaseIds) {
                v2Results.put(caseId, explanationReadiness.readinessForCase(caseId));
            }
            // v1 案件继续走批量评估；v2 已在上面逐案取号。
            Map<Long, InvestigationReadinessEvaluator.Result> merged =
                    new HashMap<>(preloadV1Readiness(cases));
            merged.putAll(v2Results);
            return merged;
        }
        return preloadV1Readiness(cases);
    }

    /** 调查契约 v1 的批量就绪评估（原 preloadReadiness 逻辑）。 */
    private Map<Long, InvestigationReadinessEvaluator.Result> preloadV1Readiness(Collection<CaseEntity> cases) {
        List<Long> caseIds = cases.stream()
                .filter(item -> item.getStatus() == CaseStatus.HOLD
                        && item.getInvestigationContractVersion() == 1)
                .map(CaseEntity::getId).toList();
        if (caseIds.isEmpty() || hypothesisRepository == null) {
            return Map.of();
        }
        Map<Long, List<InvestigationHypothesis>> hypothesesByCase = new HashMap<>();
        for (InvestigationHypothesis hypothesis : hypothesisRepository.findByCaseIdInOrderByIdAsc(caseIds)) {
            hypothesesByCase.computeIfAbsent(hypothesis.getCaseId(), key -> new ArrayList<>()).add(hypothesis);
        }
        Map<Long, List<AlertInvestigationCoverage>> coverageByCase = new HashMap<>();
        for (AlertInvestigationCoverage item : coverageRepository.findByCaseIdInOrderByAlertIdAsc(caseIds)) {
            coverageByCase.computeIfAbsent(item.getCaseId(), key -> new ArrayList<>()).add(item);
        }
        Map<Long, List<com.bank.aml.investigation.InvestigationEvidenceLink>> evidenceByCase = new HashMap<>();
        for (com.bank.aml.investigation.InvestigationEvidenceLink link :
                investigationEvidenceRepository.findByCaseIdIn(caseIds)) {
            evidenceByCase.computeIfAbsent(link.getCaseId(), key -> new ArrayList<>()).add(link);
        }
        Map<Long, InvestigationReadinessEvaluator.Result> results = new HashMap<>();
        for (Long caseId : caseIds) {
            List<InvestigationHypothesis> hypotheses = hypothesesByCase.getOrDefault(caseId, List.of());
            Map<Long, List<com.bank.aml.investigation.InvestigationEvidenceLink>> evidenceByHypothesis =
                    new HashMap<>();
            for (com.bank.aml.investigation.InvestigationEvidenceLink link :
                    evidenceByCase.getOrDefault(caseId, List.of())) {
                evidenceByHypothesis.computeIfAbsent(link.getHypothesisId(), key -> new ArrayList<>()).add(link);
            }
            results.put(caseId, readinessEvaluator.evaluate(new InvestigationReadinessEvaluator.Facts(
                    1, linkedAlerts(caseId), hypotheses,
                    coverageByCase.getOrDefault(caseId, List.of()), evidenceByHypothesis)));
        }
        return results;
    }

    private List<AmlAlert> linkedAlerts(Long caseId) {
        return alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(item -> item.getStatus() == AlertStatus.LINKED).toList();
    }

    CaseOperationsView view(CaseEntity caseEntity) {
        // 单案件查询路径：HOLD 且调查契约 v1/v2 时按需计算就绪结论，待办展示具体阻断项
        InvestigationReadinessEvaluator.Result readiness =
                caseEntity.getStatus() == CaseStatus.HOLD && caseEntity.getInvestigationContractVersion() == 1
                        ? readReadiness(caseEntity.getId())
                        : (caseEntity.getStatus() == CaseStatus.HOLD
                            && caseEntity.getInvestigationContractVersion() == 2
                            && explanationReadiness != null
                            ? explanationReadiness.readinessForCase(caseEntity.getId()) : null);
        return view(caseEntity, readiness);
    }

    private CaseOperationsView view(CaseEntity caseEntity, InvestigationReadinessEvaluator.Result preloadedReadiness) {
        List<AmlAlert> alerts = linkedAlerts(caseEntity.getId());
        PriorityAssessment assessment = priority(caseEntity, alerts);
        LocalDateTime now = LocalDateTime.now(clock);
        PhaseClock phaseClock = phaseClock(caseEntity, alerts, assessment.priority(), preloadedReadiness);
        // 调查未就绪的待办提示具体阻断项（只取前三条，避免运营面板噪声）。
        List<String> reasons = new ArrayList<>(assessment.reasons());
        if (phaseClock.phase() == OperationPhase.INVESTIGATION && caseEntity.getStatus() == CaseStatus.HOLD
                && preloadedReadiness != null && !preloadedReadiness.readyForFinalReview()) {
            preloadedReadiness.generalBlockers().stream().limit(3).forEach(reasons::add);
        }
        boolean overdue = phaseClock.dueAt() != null && !phaseClock.dueAt().isAfter(now);
        long minutesRemaining = phaseClock.dueAt() == null ? 0
                : Duration.between(now, phaseClock.dueAt()).toMinutes();
        return new CaseOperationsView(caseEntity.getId(), caseEntity.getCustomerId(), caseEntity.getCustomerName(),
                caseEntity.getStatus(), assessment.priority(), assessment.score(), List.copyOf(new LinkedHashSet<>(reasons)),
                "P1_V1_DETERMINISTIC_CASE_PRIORITY",
                phaseClock.phase(), phaseClock.responsibleRole(), phaseClock.assignedTo(),
                phaseClock.assignedUnit(), phaseClock.startedAt(), phaseClock.dueAt(), overdue,
                minutesRemaining, phaseClock.policy(), now);
    }

    private boolean visibleTo(CaseOperationsView item, String username, String role) {
        if ("ADMIN".equals(role)) return true;
        if ("REVIEWER".equals(role)) {
            return Set.of(OperationPhase.REVIEW, OperationPhase.REPORTING).contains(item.phase());
        }
        if ("ANALYST".equals(role)) {
            return item.phase() == OperationPhase.INVESTIGATION
                    || (item.phase() == OperationPhase.ENHANCED_DUE_DILIGENCE
                    && username != null && username.equals(item.assignedTo()));
        }
        return false;
    }

    private PriorityAssessment priority(CaseEntity caseEntity, List<AmlAlert> alerts) {
        int score = 20;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        Set<String> scenarios = alerts.stream().map(AmlAlert::getScenarioCode)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (String scenario : scenarios) {
            switch (scenario) {
                case "SANCTIONS_WATCHLIST" -> { score += 80; reasons.add("名单身份核验场景"); }
                case "STRUCTURING" -> { score += 50; reasons.add("疑似拆分交易规避监测"); }
                case "RAPID_MOVEMENT" -> { score += 45; reasons.add("资金快进快出场景"); }
                case "CROSS_BORDER_ANOMALY" -> { score += 45; reasons.add("异常跨境交易场景"); }
                case "COMPLEX_OWNERSHIP" -> { score += 35; reasons.add("复杂受益所有权场景"); }
                case "PROFILE_MISMATCH" -> { score += 15; reasons.add("交易与客户画像不匹配"); }
                default -> reasons.add("其他监测场景");
            }
        }
        if (alerts.size() > 1) {
            score += Math.min(20, (alerts.size() - 1) * 10);
            reasons.add("同一客户聚合 " + alerts.size() + " 条有效预警");
        }
        if ("高风险".equals(caseEntity.getRiskLevel())) {
            score = Math.max(60, score + 30);
            reasons.add("规则护栏后为高风险");
        } else if ("中风险".equals(caseEntity.getRiskLevel())) {
            score += 15;
            reasons.add("规则护栏后为中风险");
        }
        if (caseEntity.getStatus() == CaseStatus.REPORT_PENDING) {
            score = Math.max(85, score + 20);
            reasons.add("已确认可疑，等待完成报送");
        }
        if (caseEntity.getStatus() == CaseStatus.FAILED) {
            score += 15;
            reasons.add("自动调查失败，需要人工恢复");
        }
        score = Math.min(100, score);
        CasePriority priority = score >= 85 ? CasePriority.CRITICAL
                : score >= 60 ? CasePriority.HIGH : score >= 35 ? CasePriority.MEDIUM : CasePriority.NORMAL;
        if (reasons.isEmpty()) reasons.add("常规案件基线");
        return new PriorityAssessment(priority, score, List.copyOf(reasons));
    }

    private PhaseClock phaseClock(CaseEntity caseEntity, List<AmlAlert> alerts, CasePriority priority,
                                  InvestigationReadinessEvaluator.Result preloadedReadiness) {
        if (caseEntity.getStatus() == CaseStatus.DONE) {
            return new PhaseClock(OperationPhase.COMPLETED, "COMPLETED", null, "已完成",
                    fallback(caseEntity.getReviewedAt(), caseEntity), null, "P1_V1_SLA_COMPLETED");
        }
        if (caseEntity.getStatus() == CaseStatus.HOLD) {
            EnhancedDueDiligenceRequest latest = eddRepository.findTopByCaseIdOrderByRoundNoDesc(caseEntity.getId())
                    .orElse(null);
            if (latest != null && latest.getStatus() == EnhancedDueDiligenceStatus.OPEN) {
                return new PhaseClock(OperationPhase.ENHANCED_DUE_DILIGENCE, "ANALYST",
                        latest.getAssignedTo(), latest.getAssignedUnit(), latest.getRequestedAt(), latest.getDueAt(),
                        "P1_V1_EDD_EXPLICIT_DUE_AT");
            }
            LocalDateTime eddEvent = latest != null && latest.getStatus() == EnhancedDueDiligenceStatus.SUBMITTED
                    ? latest.getRespondedAt()
                    : latest != null && latest.getStatus() == EnhancedDueDiligenceStatus.CANCELLED
                    ? latest.getCancelledAt() : null;
            // 调查契约 v1：区分“待补齐调查（分析员）”与“调查就绪待复核（复核员）”。
            InvestigationReadinessEvaluator.Result readiness = caseEntity.getInvestigationContractVersion() >= 1
                    ? (preloadedReadiness != null ? preloadedReadiness : readReadiness(caseEntity.getId()))
                    : InvestigationReadinessEvaluator.Result.ready();
            if (!readiness.readyForFinalReview()) {
                // 初次补齐调查沿用案件调查起点（预警进入系统时间），改判不重置整体调查期限。
                LocalDateTime start = alerts.stream().map(AmlAlert::getCreatedAt).filter(java.util.Objects::nonNull)
                        .min(LocalDateTime::compareTo).orElse(caseEntity.getCreatedAt());
                return deadline(OperationPhase.INVESTIGATION, "ANALYST", null, "调查待补齐",
                        fallback(start, caseEntity), investigationHours(priority), "P1_V1_RISK_BASED_INVESTIGATION_SLA");
            }
            // 阶段起点只用可持久化事实：自动分析完成、有效假设/覆盖最后完成、最近 EDD 回传/撤销的最晚时间。
            LocalDateTime reviewStart = reviewPhaseStart(caseEntity, eddEvent);
            return deadline(OperationPhase.REVIEW, "REVIEWER", null, "人工复核队列",
                    fallback(reviewStart, caseEntity), reviewHours(priority), "P1_V1_RISK_BASED_REVIEW_SLA");
        }
        if (caseEntity.getStatus() == CaseStatus.REPORT_PENDING) {
            SuspiciousTransactionReport report = reportRepository.findByCaseId(caseEntity.getId()).orElse(null);
            LocalDateTime reportingStart = report != null
                    && report.getStatus() == SuspiciousTransactionReportStatus.RETURNED_FOR_CORRECTION
                    ? report.getReturnedAt() : caseEntity.getReviewedAt();
            return deadline(OperationPhase.REPORTING, "REVIEWER", null, "可疑交易报告队列",
                    fallback(reportingStart, caseEntity), reportHours(priority),
                    "P1_V1_RISK_BASED_REPORTING_SLA");
        }
        // SLA 从预警进入本系统时起算，而不是从可疑交易发生时起算；历史交易今天触发不应被误判为逾期。
        LocalDateTime start = alerts.stream().map(AmlAlert::getCreatedAt).filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(caseEntity.getCreatedAt());
        return deadline(OperationPhase.INVESTIGATION, "ANALYST", null, "反洗钱分析队列",
                fallback(start, caseEntity), investigationHours(priority), "P1_V1_RISK_BASED_INVESTIGATION_SLA");
    }

    private LocalDateTime latestExecutionCompletion(CaseEntity caseEntity) {
        return executionRepository.findByCaseIdOrderByStartedAtAsc(caseEntity.getId()).stream()
                .map(CaseExecution::getCompletedAt).filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null);
    }

    /** 单案件查询路径：按需加载调查就绪结论（队列路径已预加载，不走此分支）。 */
    private InvestigationReadinessEvaluator.Result readReadiness(Long caseId) {
        if (hypothesisRepository == null || coverageRepository == null
                || investigationEvidenceRepository == null) {
            return InvestigationReadinessEvaluator.Result.ready();
        }
        List<InvestigationHypothesis> hypotheses = hypothesisRepository.findByCaseIdOrderByIdAsc(caseId);
        List<AlertInvestigationCoverage> coverage = coverageRepository.findByCaseIdOrderByAlertIdAsc(caseId);
        Map<Long, List<com.bank.aml.investigation.InvestigationEvidenceLink>> evidenceByHypothesis = new HashMap<>();
        for (com.bank.aml.investigation.InvestigationEvidenceLink link :
                investigationEvidenceRepository.findByCaseIdOrderByCreatedAtAsc(caseId)) {
            evidenceByHypothesis.computeIfAbsent(link.getHypothesisId(), key -> new ArrayList<>()).add(link);
        }
        return readinessEvaluator.evaluate(new InvestigationReadinessEvaluator.Facts(
                1, linkedAlerts(caseId), hypotheses, coverage, evidenceByHypothesis));
    }

    /**
     * 复核阶段起点：自动分析完成、有效假设/覆盖最后完成与最近 EDD 回传/撤销的最晚时间。
     * 全部来自已持久化业务事实，不随查询时间漂移。
     */
    private LocalDateTime reviewPhaseStart(CaseEntity caseEntity, LocalDateTime eddEvent) {
        LocalDateTime start = latestExecutionCompletion(caseEntity);
        if (hypothesisRepository != null && coverageRepository != null) {
            start = maxTime(start, hypothesisRepository.findByCaseIdOrderByIdAsc(caseEntity.getId()).stream()
                    .filter(item -> item.getStatus() != HypothesisStatus.OPEN)
                    .map(InvestigationHypothesis::getUpdatedAt).filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(null));
            start = maxTime(start, coverageRepository.findByCaseIdOrderByAlertIdAsc(caseEntity.getId()).stream()
                    .filter(item -> item.getConclusion() != com.bank.aml.investigation.AlertCoverageConclusion.PENDING)
                    .map(AlertInvestigationCoverage::getUpdatedAt).filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(null));
        }
        return maxTime(start, eddEvent);
    }

    private LocalDateTime maxTime(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return right.isAfter(left) ? right : left;
    }

    private LocalDateTime fallback(LocalDateTime preferred, CaseEntity entity) {
        if (preferred != null) return preferred;
        if (entity.getUpdatedAt() != null) return entity.getUpdatedAt();
        if (entity.getCreatedAt() != null) return entity.getCreatedAt();
        return LocalDateTime.now(clock);
    }

    private PhaseClock deadline(OperationPhase phase, String role, String assignedTo, String unit,
                                LocalDateTime start, int hours, String policy) {
        return new PhaseClock(phase, role, assignedTo, unit, start, start.plusHours(hours),
                policy + "_" + hours + "H");
    }

    private int investigationHours(CasePriority priority) {
        return switch (priority) { case CRITICAL -> 4; case HIGH -> 12; case MEDIUM -> 24; case NORMAL -> 72; };
    }
    private int reviewHours(CasePriority priority) {
        return switch (priority) { case CRITICAL -> 2; case HIGH -> 4; case MEDIUM -> 8; case NORMAL -> 24; };
    }
    private int reportHours(CasePriority priority) {
        return switch (priority) { case CRITICAL -> 4; case HIGH -> 8; case MEDIUM -> 24; case NORMAL -> 48; };
    }

    private record PriorityAssessment(CasePriority priority, int score, List<String> reasons) { }
    private record PhaseClock(OperationPhase phase, String responsibleRole, String assignedTo, String assignedUnit,
                              LocalDateTime startedAt, LocalDateTime dueAt, String policy) { }
}
