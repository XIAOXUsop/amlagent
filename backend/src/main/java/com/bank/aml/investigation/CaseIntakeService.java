package com.bank.aml.investigation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.config.AmlProperties;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.messaging.WorkflowCommandService;
import com.bank.aml.observability.MetricsRecorder;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 预警分诊及其与客户案件的归并边界。 */
@Service
public class CaseIntakeService {

    private final CaseRepository caseRepository;

    private final AmlAlertRepository alertRepository;

    private final CustomerDataPort customerData;

    private final InvestigationPlaybookCatalog playbooks;

    private final InvestigationService investigationService;

    private final WorkflowCommandService workflowCommandService;

    private final MetricsRecorder metrics;

    private final AuditOutboxService auditOutbox;

    /** 与 Worker 快照装配共用同一容量上限，保证“进入执行前拒绝”与“执行中拒绝”口径一致。 */
    private final AlertSnapshotAssembler alertAssembler;

    /** v2 解释核验工作区：试点案件按单元登记预警范围（可为 null，仅存量测试）。 */
    private final ExplanationWorkspacePort explanationWorkspace;

    private final AlertScopeService alertScopeService;

    private final long futureTimestampToleranceMinutes;

    private final Clock clock;

    public CaseIntakeService(CaseRepository caseRepository, AmlAlertRepository alertRepository,
            CustomerDataPort customerData, InvestigationPlaybookCatalog playbooks,
            InvestigationService investigationService, WorkflowCommandService workflowCommandService,
            MetricsRecorder metrics, AuditOutboxService auditOutbox, AlertSnapshotAssembler alertAssembler,
            AmlProperties properties, Clock clock) {
        this(caseRepository, alertRepository, customerData, playbooks, investigationService, workflowCommandService,
                metrics, auditOutbox, alertAssembler, null, null, properties, clock);
    }

    public CaseIntakeService(CaseRepository caseRepository, AmlAlertRepository alertRepository,
            CustomerDataPort customerData, InvestigationPlaybookCatalog playbooks,
            InvestigationService investigationService, WorkflowCommandService workflowCommandService,
            MetricsRecorder metrics, AuditOutboxService auditOutbox, AlertSnapshotAssembler alertAssembler,
            ExplanationWorkspacePort explanationWorkspace, AmlProperties properties, Clock clock) {
        this(caseRepository, alertRepository, customerData, playbooks, investigationService, workflowCommandService,
                metrics, auditOutbox, alertAssembler, explanationWorkspace, null, properties, clock);
    }

    @Autowired
    public CaseIntakeService(CaseRepository caseRepository, AmlAlertRepository alertRepository,
            CustomerDataPort customerData, InvestigationPlaybookCatalog playbooks,
            InvestigationService investigationService, WorkflowCommandService workflowCommandService,
            MetricsRecorder metrics, AuditOutboxService auditOutbox, AlertSnapshotAssembler alertAssembler,
            ExplanationWorkspacePort explanationWorkspace, AlertScopeService alertScopeService,
            AmlProperties properties, Clock clock) {
        this.caseRepository = caseRepository;
        this.alertRepository = alertRepository;
        this.customerData = customerData;
        this.playbooks = playbooks;
        this.investigationService = investigationService;
        this.workflowCommandService = workflowCommandService;
        this.metrics = metrics;
        this.auditOutbox = auditOutbox;
        this.alertAssembler = alertAssembler;
        this.explanationWorkspace = explanationWorkspace;
        this.alertScopeService = alertScopeService;
        this.futureTimestampToleranceMinutes = properties.investigation().futureTimestampToleranceMinutes();
        this.clock = clock;
    }

    /** 兼容原“创建工单”入口，但底层同步创建一条真实预警并启用 P0 调查契约。 */
    @Transactional
    public CaseEntity createManualCase(String customerId, String alertRule, boolean autoProcess, String operator) {
        String reason = alertRule == null || alertRule.isBlank() ? "大额频繁跨国转账 / 夜间集中交易"
                : normalize(alertRule, 3, 500, "预警命中说明");
        InvestigationPlaybookCatalog.Playbook playbook = playbooks.resolve(null, "MANUAL_ALERT", reason);
        AmlAlert alert = newAlert("MANUAL-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
                customerId, "MANUAL_ALERT", playbook.code(), reason, LocalDateTime.now(clock), operator);
        alert = alertRepository.save(alert);
        return attachNewCase(alert, autoProcess, operator);
    }

    @Transactional
    public AlertView createAlert(String externalAlertId, String customerId, String ruleCode, String scenarioCode,
            String hitReason, LocalDateTime occurredAt, String operator) {
        String externalId = normalizeCode(externalAlertId, 3, 64, "外部预警编号");
        if (alertRepository.existsByExternalAlertId(externalId)) {
            throw new IllegalStateException("外部预警编号已存在");
        }
        String normalizedRule = normalizeCode(ruleCode, 3, 64, "监测规则码");
        String reason = normalize(hitReason, 10, 500, "预警命中说明");
        InvestigationPlaybookCatalog.Playbook playbook = playbooks.resolve(scenarioCode, normalizedRule, reason);
        AmlAlert saved = alertRepository
            .save(newAlert(externalId, customerId, normalizedRule, playbook.code(), reason, occurredAt, operator));
        auditOutbox.enqueue("ALERT_CREATE:" + externalId, operator, "ALERT_CREATE", "ALERT",
                String.valueOf(saved.getId()), "customerId=" + saved.getCustomerId() + ",ruleCode="
                        + saved.getRuleCode() + ",scenario=" + saved.getScenarioCode());
        return AlertView.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AlertView> inbox() {
        return alertRepository.findByStatusOrderByOccurredAtAsc(AlertStatus.NEW).stream().map(AlertView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertView> caseAlerts(Long caseId) {
        requireCase(caseId);
        return alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream().map(AlertView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CaseEntity> candidateCases(Long alertId) {
        AmlAlert alert = alertRepository.findById(alertId).orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        return caseRepository.findByCustomerIdAndStatusOrderByCreatedAtDesc(alert.getCustomerId(), CaseStatus.PENDING)
            .stream()
            .filter(item -> item.getInvestigationContractVersion() >= 1)
            .toList();
    }

    @Transactional
    public CaseEntity createCaseFromAlert(Long alertId, int expectedRevision, boolean autoProcess, String operator) {
        return createCaseFromAlert(alertId, expectedRevision, autoProcess, operator, false);
    }

    /**
     * v2 试点显式建案（§17.2）：enableExplanationPolicy=true 时案件契约版本=2， 预警归并后进入解释核验工作区；不做隐式升级，存量
     * v1 案件保持原语义。
     */
    @Transactional
    public CaseEntity createCaseFromAlert(Long alertId, int expectedRevision, boolean autoProcess, String operator,
            boolean enableExplanationPolicy) {
        AmlAlert alert = lockNewAlert(alertId, expectedRevision);
        if (enableExplanationPolicy && alertScopeService != null && alertScopeService.frozenScope(alertId).isEmpty()) {
            throw new IllegalStateException("启用解释政策前必须先通过生产入口冻结预警命中范围");
        }
        CaseEntity created = attachNewCase(alert, autoProcess, operator, enableExplanationPolicy);
        if (created.getInvestigationContractVersion() == 2 && explanationWorkspace != null) {
            explanationWorkspace.ensureUnitsForLinkedAlerts(created.getId(),
                    alertRepository.findByCaseIdOrderByOccurredAtAsc(created.getId())
                        .stream()
                        .filter(item -> item.getStatus() == AlertStatus.LINKED)
                        .toList(),
                    operator);
        }
        return created;
    }

    @Transactional
    public AlertView linkToCase(Long alertId, Long caseId, int expectedRevision, String reason, String operator) {
        // 与拆分/覆盖更新保持“案件 → 预警”的固定锁顺序，避免与并发拆分互相等待；
        // 锁定案件后再做归并边界与容量校验，保证已入队 PENDING 案件不会在等待 Worker 时被归并至超限。
        CaseEntity target = caseRepository.findByIdForUpdate(caseId)
            .orElseThrow(() -> new IllegalArgumentException("目标案件不存在"));
        if (target.getStatus() != CaseStatus.PENDING) {
            throw new IllegalStateException("只能向尚未开始调查的案件归并预警");
        }
        if (target.getInvestigationContractVersion() < 1) {
            throw new IllegalStateException("存量兼容案件不能接收新预警，请为预警创建新案件");
        }
        long linkedCount = alertRepository.countByCaseIdAndStatus(caseId, AlertStatus.LINKED);
        if (linkedCount + 1 > alertAssembler.capacity()) {
            throw new IllegalStateException("归并后关联预警数量 " + (linkedCount + 1) + " 将超出单次尽调容量上限 "
                    + alertAssembler.capacity() + "；案件保持可调整状态，请先对现有预警执行拆分或新建案件后再归并");
        }
        AmlAlert alert = lockNewAlert(alertId, expectedRevision);
        if (!target.getCustomerId().equals(alert.getCustomerId())) {
            throw new IllegalArgumentException("只能归并同一客户的预警");
        }
        if (isV2Case(target) && alertScopeService != null && alertScopeService.frozenScope(alertId).isEmpty()) {
            throw new IllegalStateException("归并到解释政策案件前必须先冻结预警命中范围");
        }
        String normalizedReason = normalize(reason, 10, 500, "归并原因");
        alert.setStatus(AlertStatus.LINKED);
        alert.setCaseId(caseId);
        alert.setResolutionReason(normalizedReason);
        alert.setRevision(alert.getRevision() + 1);
        AmlAlert saved = alertRepository.save(alert);
        investigationService.initializeForAlert(caseId, saved, operator);
        if (isV2Case(target)) {
            explanationWorkspace.ensureUnitsForLinkedAlerts(caseId,
                    alertRepository.findByCaseIdOrderByOccurredAtAsc(caseId)
                        .stream()
                        .filter(item -> item.getStatus() == AlertStatus.LINKED)
                        .toList(),
                    operator);
        }
        refreshCaseAlertSummary(target);
        auditOutbox.enqueue("ALERT_LINK:" + alertId + ":" + expectedRevision, operator, "ALERT_LINK_CASE", "ALERT",
                String.valueOf(alertId), "caseId=" + caseId + ",reasonLen=" + normalizedReason.length());
        return AlertView.from(saved);
    }

    private boolean isV2Case(CaseEntity caseEntity) {
        return caseEntity.getInvestigationContractVersion() == 2 && explanationWorkspace != null;
    }

    @Transactional
    public CaseEntity splitToNewCase(Long alertId, int expectedRevision, boolean autoProcess, String reason,
            String operator) {
        AmlAlert observed = alertRepository.findById(alertId).orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if (observed.getStatus() != AlertStatus.LINKED || observed.getCaseId() == null) {
            throw new IllegalStateException("预警状态或版本已变化，请刷新后重试");
        }
        Long sourceCaseId = observed.getCaseId();
        // 与覆盖结论更新保持“案件 → 预警”的固定锁顺序，避免并发拆分/研判时互相等待。
        CaseEntity source = caseRepository.findByIdForUpdate(sourceCaseId)
            .orElseThrow(() -> new IllegalStateException("来源案件不存在"));
        AmlAlert alert = alertRepository.findByIdForUpdate(alertId)
            .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if (alert.getStatus() != AlertStatus.LINKED || alert.getRevision() != expectedRevision
                || !sourceCaseId.equals(alert.getCaseId())) {
            throw new IllegalStateException("预警状态或版本已变化，请刷新后重试");
        }
        if (source.getStatus() != CaseStatus.PENDING) {
            // 受控恢复：容量超限等进入执行前的失败会把案件置为 FAILED；只要确认没有产生任何
            // 调查产出（报告/原始模型输出为空，且 validateCanSplit 继续兑换无证据、无已决假设、
            // 无已决覆盖），就允许在不与重试消费者竞态的前提下拆分，拆分后再人工重试原案件。
            boolean recoverableFailure = source.getStatus() == CaseStatus.FAILED && source.getReportJson() == null
                    && source.getRawReportJson() == null && source.getInvestigationContractVersion() >= 1;
            if (!recoverableFailure) {
                throw new IllegalStateException("调查开始后不能拆分预警，需先撤回调查流程");
            }
        }
        if (alertRepository.countByCaseIdAndStatus(sourceCaseId, AlertStatus.LINKED) < 2) {
            throw new IllegalStateException("单预警案件无需拆分");
        }
        investigationService.validateCanSplit(sourceCaseId);
        String normalizedReason = normalize(reason, 10, 500, "拆分原因");
        CaseEntity target = createCase(alert, operator);
        alert.setCaseId(target.getId());
        alert.setResolutionReason(normalizedReason);
        alert.setRevision(alert.getRevision() + 1);
        AmlAlert saved = alertRepository.save(alert);
        InvestigationHypothesis hypothesis = investigationService.initializeForAlert(target.getId(), saved, operator);
        if (explanationWorkspace != null && target.getInvestigationContractVersion() == 2) {
            explanationWorkspace.ensureUnitsForLinkedAlerts(target.getId(), List.of(saved), operator);
            // 源案件范围更正：被拆走预警的单元提交失效（有来源：拆分操作）。
            explanationWorkspace.removeUnitForAlert(source.getId(), alertId, "ALERT_SPLIT_TO_CASE_" + target.getId(),
                    operator);
        }
        investigationService.resetCoverageForSplit(alertId, target.getId(), hypothesis.getId());
        investigationService.removeUnusedOpenHypotheses(sourceCaseId);
        refreshCaseAlertSummary(source);
        refreshCaseAlertSummary(target);
        if (autoProcess)
            workflowCommandService.enqueueCaseCreated(target.getId());
        auditOutbox.enqueue("ALERT_SPLIT:" + alertId + ":" + expectedRevision, operator, "ALERT_SPLIT_CASE", "ALERT",
                String.valueOf(alertId), "sourceCaseId=" + sourceCaseId + ",targetCaseId=" + target.getId()
                        + ",reasonLen=" + normalizedReason.length());
        return target;
    }

    @Transactional
    public AlertView closeDuplicate(Long alertId, int expectedRevision, String reason, String operator) {
        AmlAlert alert = lockNewAlert(alertId, expectedRevision);
        String normalizedReason = normalize(reason, 10, 500, "重复预警说明");
        alert.setStatus(AlertStatus.DUPLICATE);
        alert.setResolutionReason(normalizedReason);
        alert.setRevision(alert.getRevision() + 1);
        AmlAlert saved = alertRepository.save(alert);
        auditOutbox.enqueue("ALERT_DUPLICATE:" + alertId + ":" + expectedRevision, operator, "ALERT_CLOSE_DUPLICATE",
                "ALERT", String.valueOf(alertId), "reasonLen=" + normalizedReason.length());
        return AlertView.from(saved);
    }

    private CaseEntity attachNewCase(AmlAlert alert, boolean autoProcess, String operator) {
        return attachNewCase(alert, autoProcess, operator, false);
    }

    private CaseEntity attachNewCase(AmlAlert alert, boolean autoProcess, String operator,
            boolean enableExplanationPolicy) {
        CaseEntity caseEntity = createCase(alert, operator, enableExplanationPolicy);
        alert.setStatus(AlertStatus.LINKED);
        alert.setCaseId(caseEntity.getId());
        alert.setResolutionReason("创建新案件");
        alert.setRevision(alert.getRevision() + 1);
        AmlAlert savedAlert = alertRepository.save(alert);
        investigationService.initializeForAlert(caseEntity.getId(), savedAlert, operator);
        if (autoProcess)
            workflowCommandService.enqueueCaseCreated(caseEntity.getId());
        auditOutbox.enqueue("ALERT_NEW_CASE:" + savedAlert.getId() + ":" + expectedPreviousRevision(savedAlert),
                operator, "ALERT_CREATE_CASE", "ALERT", String.valueOf(savedAlert.getId()),
                "caseId=" + caseEntity.getId() + ",scenario=" + savedAlert.getScenarioCode());
        return caseEntity;
    }

    private int expectedPreviousRevision(AmlAlert alert) {
        return Math.max(0, alert.getRevision() - 1);
    }

    private CaseEntity createCase(AmlAlert alert, String operator) {
        return createCase(alert, operator, false);
    }

    private CaseEntity createCase(AmlAlert alert, String operator, boolean enableExplanationPolicy) {
        CustomerProfile customer = requireCustomer(alert.getCustomerId());
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCustomerId(customer.id());
        caseEntity.setCustomerName(customer.name());
        caseEntity.setAlertRule(
                alert.getHitReason().length() <= 255 ? alert.getHitReason() : alert.getHitReason().substring(0, 255));
        caseEntity.setStatus(CaseStatus.PENDING);
        // 契约版本：1=调查契约 v1；2=解释核验政策（v2 试点显式开启，§17.2）
        caseEntity.setInvestigationContractVersion(enableExplanationPolicy ? 2 : 1);
        CaseEntity saved = caseRepository.save(caseEntity);
        metrics.caseCreated();
        return saved;
    }

    private void refreshCaseAlertSummary(CaseEntity caseEntity) {
        List<String> ruleCodes = alertRepository.findByCaseIdOrderByOccurredAtAsc(caseEntity.getId())
            .stream()
            .filter(alert -> alert.getStatus() == AlertStatus.LINKED)
            .map(AmlAlert::getRuleCode)
            .distinct()
            .toList();
        String summary = ruleCodes.size() <= 1 ? (ruleCodes.isEmpty() ? "暂无有效关联预警" : ruleCodes.get(0))
                : "多预警归并：" + String.join("、", ruleCodes);
        caseEntity.setAlertRule(summary.length() <= 255 ? summary : summary.substring(0, 255));
        caseRepository.save(caseEntity);
    }

    private AmlAlert lockNewAlert(Long alertId, int expectedRevision) {
        AmlAlert alert = alertRepository.findByIdForUpdate(alertId)
            .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if (alert.getStatus() != AlertStatus.NEW || alert.getRevision() != expectedRevision) {
            throw new IllegalStateException("预警已被其他人分诊或版本已变化，请刷新后重试");
        }
        return alert;
    }

    private AmlAlert newAlert(String externalAlertId, String customerId, String ruleCode, String scenarioCode,
            String hitReason, LocalDateTime occurredAt, String operator) {
        CustomerProfile customer = requireCustomer(customerId);
        AmlAlert alert = new AmlAlert();
        alert.setExternalAlertId(externalAlertId);
        alert.setCustomerId(customer.id());
        alert.setRuleCode(ruleCode);
        alert.setScenarioCode(scenarioCode);
        alert.setHitReason(hitReason);
        alert.setOccurredAt(occurredAt == null ? LocalDateTime.now(clock) : occurredAt);
        if (alert.getOccurredAt().isAfter(LocalDateTime.now(clock).plusMinutes(futureTimestampToleranceMinutes))) {
            throw new IllegalArgumentException("预警发生时间不能晚于当前时间");
        }
        alert.setStatus(AlertStatus.NEW);
        alert.setCreatedBy(operator);
        return alert;
    }

    private CaseEntity requireCase(Long caseId) {
        return caseRepository.findById(caseId).orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private CustomerProfile requireCustomer(String customerId) {
        String normalized = customerId == null ? "" : customerId.trim();
        return customerData.findCustomer(normalized)
            .orElseThrow(() -> new NonRetryableWorkflowException("客户不存在：" + normalized));
    }

    private String normalizeCode(String value, int min, int max, String field) {
        String normalized = normalize(value, min, max, field).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._:/-]*")) {
            throw new IllegalArgumentException(field + "格式非法");
        }
        return normalized;
    }

    private String normalize(String value, int min, int max, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + "需为 " + min + " ~ " + max + " 个有效字符");
        }
        return normalized;
    }

}
