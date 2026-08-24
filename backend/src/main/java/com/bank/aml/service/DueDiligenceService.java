package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceAgentFactory;
import com.bank.aml.agent.AgentAnalysis;
import com.bank.aml.agent.AgentReportStabilizer;
import com.bank.aml.agent.DueDiligenceContext;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.InvestigationSnapshotFactory;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.agent.validation.AgentOutputValidator;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.RetryableWorkflowException;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.common.fault.FaultInjector;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.config.LlmProviderProperties;
import com.bank.aml.cost.CostRouter;
import com.bank.aml.security.PromptInjectionGuard;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.entity.CaseLogEntity;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.messaging.WorkflowCommandService;
import com.bank.aml.messaging.ExecutionLease;
import com.bank.aml.observability.MetricsRecorder;
import com.bank.aml.tools.ToolExecutionTrace;
import com.bank.aml.tools.ToolExecutionTraceEntity;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecution.ExecutionStatus;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 尽调工作流编排：
 * PENDING → PLANNING → COLLECTING → REASONING → GUARDRAIL → REPORTING → DONE/HOLD/FAILED
 * <p>阶段执行均写入 {@link CaseExecution} 检查点；失败按异常类型抛出，由 Worker 决定重试/死信。
 */
@Service
public class DueDiligenceService {

    private static final Logger log = LoggerFactory.getLogger(DueDiligenceService.class);

    private final CaseRepository caseRepository;
    private final CaseLogRepository caseLogRepository;
    private final CaseExecutionRepository caseExecutionRepository;
    private final WorkflowCommandService workflowCommandService;
    private final MetricsRecorder metrics;
    private final DueDiligenceAgentFactory agentFactory;
    private final RuleBasedReporter ruleReporter;
    private final GuardrailEngine guardrailEngine;
    private final AgentOutputValidator agentOutputValidator;
    private final FinalDecisionAssembler finalDecisionAssembler;
    private final InvestigationSnapshotFactory snapshotFactory;
    private final WorkflowEventService workflowEventService;
    private final CustomerDataPort dataSource;
    private final FaultInjector faultInjector;
    private final PromptInjectionGuard promptInjectionGuard;
    private final CostRouter costRouter;
    private final boolean ruleFallbackEnabled;
    private final boolean summaryEnabled;
    private final FinalReportStreamingService finalReportStreamingService;
    private final SnapshotArchiveService snapshotArchiveService;
    private final ObjectMapper objectMapper;
    private final ToolExecutionTraceRepository toolTraceRepository;
    private final LlmProperties llmProperties;

    /** 阶段耗时测量（每工单独立） */
    private final ThreadLocal<LocalDateTime> lastStageAt = new ThreadLocal<>();
    /** 当前执行租约（每工单独立线程），用于旧 Worker 副作用隔离 */
    private final ThreadLocal<ExecutionLease> currentLease = new ThreadLocal<>();

    public DueDiligenceService(CaseRepository caseRepository, CaseLogRepository caseLogRepository,
                               CaseExecutionRepository caseExecutionRepository, WorkflowCommandService workflowCommandService,
                               MetricsRecorder metrics,
                               DueDiligenceAgentFactory agentFactory, RuleBasedReporter ruleReporter,
                               GuardrailEngine guardrailEngine, AgentOutputValidator agentOutputValidator,
                               FinalDecisionAssembler finalDecisionAssembler,
                               InvestigationSnapshotFactory snapshotFactory,
                               WorkflowEventService workflowEventService,
                               CustomerDataPort dataSource, FaultInjector faultInjector,
                               PromptInjectionGuard promptInjectionGuard, CostRouter costRouter,
                               @Value("${aml.cost-routing.rule-fallback-enabled:false}") boolean ruleFallbackEnabled,
                               @Value("${aml.cost-routing.summary-enabled:false}") boolean summaryEnabled,
                               FinalReportStreamingService finalReportStreamingService, ObjectMapper objectMapper,
                               SnapshotArchiveService snapshotArchiveService,
                               ToolExecutionTraceRepository toolTraceRepository, LlmProperties llmProperties) {
        this.caseRepository = caseRepository;
        this.caseLogRepository = caseLogRepository;
        this.caseExecutionRepository = caseExecutionRepository;
        this.workflowCommandService = workflowCommandService;
        this.metrics = metrics;
        this.agentFactory = agentFactory;
        this.ruleReporter = ruleReporter;
        this.guardrailEngine = guardrailEngine;
        this.agentOutputValidator = agentOutputValidator;
        this.finalDecisionAssembler = finalDecisionAssembler;
        this.snapshotFactory = snapshotFactory;
        this.workflowEventService = workflowEventService;
        this.dataSource = dataSource;
        this.faultInjector = faultInjector;
        this.promptInjectionGuard = promptInjectionGuard;
        this.costRouter = costRouter;
        this.ruleFallbackEnabled = ruleFallbackEnabled;
        this.summaryEnabled = summaryEnabled;
        this.finalReportStreamingService = finalReportStreamingService;
        this.snapshotArchiveService = snapshotArchiveService;
        this.objectMapper = objectMapper;
        this.toolTraceRepository = toolTraceRepository;
        this.llmProperties = llmProperties;
    }

    /** 创建预警工单；autoProcess=true 时与工单同事务写入 Outbox，自动触发尽调 */
    @Transactional
    public CaseEntity createCase(String customerId, String alertRule, boolean autoProcess) {
        CustomerProfile customer = dataSource.findCustomer(customerId)
                .orElseThrow(() -> new NonRetryableWorkflowException("客户不存在：" + customerId));
        CaseEntity c = new CaseEntity();
        c.setCustomerId(customer.id());
        c.setCustomerName(customer.name());
        c.setAlertRule(alertRule == null || alertRule.isBlank()
                ? "大额频繁跨国转账 / 夜间集中交易" : alertRule);
        c.setStatus(CaseStatus.PENDING);
        CaseEntity saved = caseRepository.save(c);
        metrics.caseCreated();

        // Transactional Outbox：工单与 Outbox 事件同事务写入；
        // 发布器随后异步扫描投递到 Redis Streams（此时事务早已提交，Worker 读到的是已提交工单）
        if (autoProcess) {
            enqueue(saved.getId());
        }
        return saved;
    }

    /** 写入 Outbox（首次入队，由发布器投递到 Redis Streams） */
    public void enqueue(Long caseId) {
        workflowCommandService.enqueueCaseCreated(caseId);
    }

    /** 手动触发：仅 PENDING 工单可触发，否则返回 409（不再静默忽略） */
    public void trigger(Long caseId) {
        CaseEntity c = getCase(caseId);
        if (c.getStatus() != CaseStatus.PENDING) {
            throw new WorkflowStateConflictException(caseId, c.getStatus(), java.util.Set.of(CaseStatus.PENDING));
        }
        workflowCommandService.triggerManual(caseId, c.getExecutionVersion());
    }

    /** 人工重试：重置为 PENDING 并重新写入 Outbox 入队（由 {@link WorkflowCommandService} 原子完成） */
    public CaseEntity retry(Long caseId) {
        return workflowCommandService.retryManual(caseId);
    }

    /**
     * 执行尽调工作流（由 Worker 消费任务后调用）。
     * 正常完成置 DONE/HOLD；失败按异常类型抛出（重试/不可重试），由 Worker 决定重试策略。
     */
    public CaseEntity process(Long caseId, String worker, int executionVersion, ExecutionLease lease) {
        lastStageAt.remove();
        currentLease.set(lease);
        DueDiligenceReport streamingReport = null;
        CaseEntity c = caseRepository.findById(caseId)
                .orElseThrow(() -> new NonRetryableWorkflowException("工单不存在：" + caseId));

        try {
            // 0. Prompt 注入检测（代码层确定性防护，命中仍继续由模型按 prompt 隔离处理）
            PromptInjectionGuard.InjectionResult injection = promptInjectionGuard.scan(c.getAlertRule());
            if (injection.suspicious()) {
                record(c, WorkflowStage.PLANNING, "⚠ 检测到疑似提示注入：" + injection.matchedPatterns());
                c.setFailureMessage("检测到疑似提示注入，已记录");
                caseRepository.save(c);
            }

            // 1. 任务规划
            record(c, WorkflowStage.PLANNING, "解析预警工单，拆解子任务：\n" + String.join("\n", planTasks(c.getAlertRule())));

            // 2. 数据采集（Snapshot First：先冻结业务事实，再让 Agent 基于快照推理）
            CostRouter.Route route = costRouter.route(c.getAlertRule(), ruleFallbackEnabled, summaryEnabled);
            record(c, WorkflowStage.COLLECTING, "成本路由：" + route);

            CustomerProfile customer = dataSource.findCustomer(c.getCustomerId())
                    .orElseThrow(() -> new NonRetryableWorkflowException("客户不存在：" + c.getCustomerId()));
            InvestigationSnapshot snapshot = snapshotFactory.create(c.getId(), executionVersion, customer, null, c.getAlertRule());
            // 审计前置：快照无法持久化时禁止继续调用模型，避免产出无法回放的合规结论。
            snapshotArchiveService.archive(snapshot);
            record(c, WorkflowStage.COLLECTING, "尽调快照已冻结 snapshotId=" + snapshot.snapshotId()
                    + " sourceDigest=" + snapshot.sourceDigest());

            DueDiligenceContext context = buildContext(snapshot, c);
            record(c, WorkflowStage.COLLECTING, "调用工具并行采集数据（交易画像 / 股权穿透 / 黑名单 / 法规检索）...");
            faultInjector.inject(WorkflowStage.COLLECTING); // 故障注入埋点（默认关闭）

            DueDiligenceReport report = null;
            DueDiligenceReport rawAgentReport = null;
            Exception agentFailure = null;
            List<String> outputViolations = List.of();
            boolean forceSafetyHold = false;
            String reportSource = "AGENT";
            // 每个 executionVersion 只保留本次模型原始输出，禁止重试时沿用上一轮报告。
            c.setRawReportJson(null);
            // 预警文本来自请求侧，不能授权跳过 Agent；所有业务工单均执行主 Agent。
            DueDiligenceAgentFactory.AgentWithTools agentWithTools = null;
            try {
                agentWithTools = agentFactory.createWithTraces(snapshot);
                AgentAnalysis rawAgentAnalysis = agentWithTools.agent().investigate(context.toPrompt());
                rawAgentReport = DueDiligenceReport.fromAnalysis(customer.id(), customer.name(), rawAgentAnalysis);
                if (rawAgentReport != null) {
                    // 原始留痕只保存模型边界 DTO，禁止把后端随后补入的可信身份字段混入模型原文。
                    c.setRawReportJson(writeJson(rawAgentAnalysis));
                    DueDiligenceReport stabilizedReport = AgentReportStabilizer
                            .attachFrozenLegalEvidence(snapshot, rawAgentReport,
                                    agentWithTools.tools().traces().stream()
                                            .filter(trace -> trace.success() && "searchLegal".equals(trace.toolName()))
                                            .flatMap(trace -> trace.evidenceIds().stream())
                                            .distinct()
                                            .toList());
                    AgentOutputValidator.ValidationResult validation = agentOutputValidator.validate(snapshot, stabilizedReport);
                    if (validation.valid()) {
                        report = stabilizedReport;
                    } else {
                        outputViolations = validation.violations();
                        record(c, WorkflowStage.REASONING,
                                "Agent 输出未通过生产契约校验：" + String.join(",", outputViolations));
                    }
                }
            } catch (Exception e) {
                // 保留完整堆栈：Agent 故障是运维关键信号，不能只记一句话被静默掩盖
                log.warn("Agent 调用失败，进入规则降级 caseId={}", caseId, e);
                agentFailure = e;
                report = null;
            } finally {
                // Agent 异常/工具轮次耗尽时也保留已执行的部分工具轨迹
                if (agentWithTools != null) {
                    persistToolTraces(c, snapshot, agentWithTools.tools().traces());
                }
            }

            // Agent 长调用期间可能被接管：租约丢失则停止后续阶段，不再写 Guardrail/报告/终态
            if (lease != null && !lease.isValid()) {
                log.warn("工单 {} Agent 调用后租约已丢失，跳过 Guardrail 与报告", caseId);
                return c;
            }

            // 3. 风险推理
            record(c, WorkflowStage.REASONING, "综合研判交易 / 股权 / 黑名单 / 法规证据，完成风险推理。");

            if (report == null && !outputViolations.isEmpty()) {
                // 模型已经返回但违反生产契约：不得用自动规则报告掩盖，必须显式转人工。
                report = ruleReporter.generateSafetyHold(snapshot, c.getAlertRule(), outputViolations);
                reportSource = "AGENT_INVALID_HOLD";
                forceSafetyHold = true;
                metrics.caseLlmFallback();
            } else if (report == null && ruleFallbackEnabled) {
                DueDiligenceReport fallback = ruleReporter.generate(snapshot, c.getAlertRule());
                if (fallback != null) {
                    report = fallback;
                    reportSource = "RULE_FALLBACK"; // 规则降级输出，不再是模型评级
                    metrics.caseLlmFallback(); // 降级成功也单独计数，便于区分正常成功与降级成功
                }
            }
            if (report == null) {
                throw new RetryableWorkflowException(
                        "Agent 调用失败且规则降级未启用", agentFailure);
            }

            // 4. 规则护栏（基于与 Agent 相同的冻结快照，不二次读取数据源）
            record(c, WorkflowStage.GUARDRAIL, "执行规则护栏校验（制裁名单 / 评级一致性）...");
            String decisionInputRiskLevel = report.riskLevel();
            String rawAgentRiskLevel = rawAgentReport == null ? decisionInputRiskLevel : rawAgentReport.riskLevel();
            GuardrailEngine.GuardrailResult gr = guardrailEngine.apply(snapshot, report);
            gr.decision().triggeredRules().forEach(r -> record(c, WorkflowStage.GUARDRAIL,
                    "触发规则【" + r.ruleCode() + " v" + r.ruleVersion() + "】→ " + r.targetRiskLevel()
                            + "，动作 " + r.action() + "，证据：" + r.evidence()));
            gr.corrections().forEach(corr -> record(c, WorkflowStage.GUARDRAIL, corr));
            if (!gr.corrections().isEmpty()) {
                metrics.guardrailCorrection();
            }
            // 同步最终报告字段（风险评级 / 人工复核标志 / 处置代码）
            report = finalDecisionAssembler.assemble(report, gr, forceSafetyHold);
            AgentOutputValidator.ValidationResult finalValidation = agentOutputValidator.validate(snapshot, report);
            if (!finalValidation.valid()) {
                throw new NonRetryableWorkflowException(
                        "最终决策报告未通过生产契约校验：" + String.join(",", finalValidation.violations()));
            }
            if (!gr.finalRiskLevel().equals(decisionInputRiskLevel)) {
                record(c, WorkflowStage.GUARDRAIL, "评级由【" + decisionInputRiskLevel + "】修正为：【" + gr.finalRiskLevel() + "】");
            }
            c.setRiskLevel(gr.finalRiskLevel());
            c.setRawRiskLevel(rawAgentRiskLevel);
            c.setStatus(forceSafetyHold || gr.mustEscalate() ? CaseStatus.HOLD : CaseStatus.DONE);
            if (c.getStatus() == CaseStatus.HOLD) {
                metrics.caseHold();
                String escalateRules = gr.decision().triggeredRules().stream()
                        .filter(r -> "MANUAL_REVIEW".equals(r.action()))
                        .map(r -> r.ruleCode()).toList().toString();
                record(c, WorkflowStage.GUARDRAIL, "触发转人工（规则 " + escalateRules + "），工单进入人工复核队列。");
                if (forceSafetyHold) {
                    record(c, WorkflowStage.GUARDRAIL, "Agent 输出契约不合规，安全策略禁止自动完成。");
                }
            }

            // 5. 报告生成与落库
            c.setReportJson(writeJson(report));
            c.setSummary(report.conclusion());
            c.setReportSource(reportSource);
            c.setSnapshotId(snapshot.snapshotId());
            // 记录实际模型提供商/模型名/是否降级，避免只保存 reportSource=AGENT 无法追溯真实模型
            LlmProviderProperties activeModel = llmProperties.active();
            c.setModelProvider(llmProperties.getActiveProvider());
            c.setModelName(activeModel.getModelName());
            c.setModelFallback(activeModel.typeEnum() == LlmProperties.ProviderType.MOCK || !activeModel.hasApiKey()
                    || !"AGENT".equals(reportSource));
            record(c, WorkflowStage.REPORTING, "尽调初审报告已生成并归档，来源=" + reportSource
                    + "，模型=" + c.getModelProvider() + "/" + c.getModelName() + "，最终评级：" + c.getRiskLevel());
            record(c, WorkflowStage.DONE, "尽调完成。评级：" + c.getRiskLevel() + "，状态：" + c.getStatus());

            // 可选报告流：最终报告落库后，仅按结构化风险/发现/动作确定性渲染，不再调用模型。
            if (route == CostRouter.Route.AGENT_WITH_SUMMARY) {
                // 仅准备输入；必须等终态报告成功落库后再启动，避免摘要先于最终状态到达前端。
                streamingReport = report;
            }
        } catch (NonRetryableWorkflowException e) {
            metrics.caseFailed();
            throw e;
        } catch (RetryableWorkflowException e) {
            metrics.caseFailed();
            throw e;
        } catch (Exception e) {
            metrics.caseFailed();
            // 未分类异常必须保留根因堆栈；仅向工单暴露通用错误码，避免敏感内容落库。
            log.error("尽调工作流出现未分类异常 caseId={} executionVersion={}", caseId, executionVersion, e);
            throw new RetryableWorkflowException("未知异常", e);
        } finally {
            lastStageAt.remove();
            currentLease.remove();
        }
        // 终态原子落库：绑定 worker+executionVersion，被接管后的陈旧写入不生效（0 行更新被丢弃）
        int updated = caseRepository.finishCase(c.getId(), worker, executionVersion,
                c.getStatus(), c.getRiskLevel(), c.getRawRiskLevel(), c.getReportJson(), c.getRawReportJson(), c.getSummary(),
                c.getReportSource(), c.getSnapshotId(),
                c.getModelProvider(), c.getModelName(), c.isModelFallback());
        if (updated == 0) {
            log.warn("工单 {} 终态落库被丢弃（已被接管，worker/version 不匹配）", caseId);
        } else {
            // 落库成功且仍持有租约：可选摘要结束后再关闭 SSE；否则立即推终态。
            if (lease == null || lease.isValid()) {
                if (streamingReport == null
                        || !finalReportStreamingService.stream(c.getId(), c.getStatus(), streamingReport, lease)) {
                    workflowEventService.complete(caseId, c.getStatus());
                }
            }
        }
        return c;
    }

    public org.springframework.data.domain.Page<CaseEntity> listCasesPageable(org.springframework.data.domain.Pageable pageable) {
        return caseRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /** 全量工单状态统计（供看板态势概览，非当前页局部统计） */
    public CaseStats caseStats() {
        long total = caseRepository.count();
        long pending = caseRepository.countByStatus(CaseStatus.PENDING);
        long running = caseRepository.countByStatus(CaseStatus.RUNNING);
        long hold = caseRepository.countByStatus(CaseStatus.HOLD);
        long done = caseRepository.countByStatus(CaseStatus.DONE);
        long failed = caseRepository.countByStatus(CaseStatus.FAILED);
        return new CaseStats(total, pending, running, hold, done, failed);
    }

    public record CaseStats(long total, long pending, long running, long hold, long done, long failed) {
    }

    public List<CaseLogEntity> listLogs(Long caseId) {
        return caseLogRepository.findByCaseIdOrderByCreatedAtAsc(caseId);
    }

    public CaseEntity getCase(Long caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private DueDiligenceContext buildContext(InvestigationSnapshot snapshot, CaseEntity c) {
        CustomerProfile customer = snapshot.customer();
        String asOfDate = snapshot.asOfTime().toString();
        return new DueDiligenceContext(
                c.getId(), customer.id(), customer.type(), asOfDate, snapshot.legalKeywords(),
                c.getAlertRule(), "请基于冻结证据独立判断风险等级、证据充分性和后续处置，不预设风险结论。");
    }

    private List<String> planTasks(String alertRule) {
        return List.of(
                "① 提取客户近 180 天交易画像（金额 / 频次 / 夜间 / 跨境 / 大额）",
                "② 穿透股权结构与关联人，识别最终受益人（UBO）",
                "③ 检索涉诉与制裁黑名单（OFAC / 国内名单）",
                "④ 匹配反洗钱监管合规条文（RAG）"
        );
    }

    /** 记录阶段日志 + SSE 推送 + 执行检查点（case_execution） */
    private void record(CaseEntity c, WorkflowStage stage, String content) {
        ExecutionLease lease = currentLease.get();
        if (lease != null && !lease.isValid()) {
            // 租约已丢失：旧 Worker 不再产生日志/SSE/检查点等对用户可见的副作用
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        CaseLogEntity l = new CaseLogEntity();
        l.setCaseId(c.getId());
        l.setStage(stage);
        l.setContent(content);
        caseLogRepository.save(l);
        workflowEventService.emit(c.getId(), stage, content);

        CaseExecution exec = new CaseExecution();
        exec.setCaseId(c.getId());
        exec.setExecutionVersion(c.getExecutionVersion());
        exec.setStage(stage);
        exec.setInputDigest(truncate(content, 200));
        exec.setOutputJson(truncate(content, 500));
        exec.setStatus(ExecutionStatus.SUCCESS);
        exec.setStartedAt(now);
        exec.setCompletedAt(now);
        LocalDateTime prev = lastStageAt.get();
        long durationMs = prev == null ? 0L : Duration.between(prev, now).toMillis();
        exec.setDurationMs(durationMs);
        lastStageAt.set(now);
        caseExecutionRepository.save(exec);
        metrics.recordStageDuration(stage.name(), durationMs);
    }

    /** 持久化工具调用轨迹（不落参数明文）：写入 tool_execution_trace 表 + 工单日志可读摘要 */
    private void persistToolTraces(CaseEntity c, InvestigationSnapshot snapshot, List<ToolExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        long seq = 0;
        for (ToolExecutionTrace t : traces) {
            ToolExecutionTraceEntity entity = new ToolExecutionTraceEntity();
            entity.setCaseId(c.getId());
            entity.setExecutionVersion(c.getExecutionVersion());
            entity.setSnapshotId(snapshot.snapshotId());
            entity.setSequenceNo(seq++);
            entity.setToolName(t.toolName());
            entity.setRequested(true);
            entity.setExecuted(true);
            entity.setSuccess(t.success());
            entity.setArgumentValid(t.argumentValid());
            entity.setDurationMs(t.durationMs());
            entity.setResultDigest(t.resultDigest());
            entity.setEvidenceIdsJson(t.evidenceIds() == null || t.evidenceIds().isEmpty()
                    ? null : writeJson(t.evidenceIds()));
            entity.setErrorCode(t.errorCode());
            toolTraceRepository.save(entity);
        }
        String summary = traces.stream()
                .map(t -> t.toolName() + (t.success() ? "✓" : "✗") + "(" + t.durationMs() + "ms)")
                .collect(java.util.stream.Collectors.joining(", "));
        record(c, WorkflowStage.COLLECTING, "工具调用轨迹：" + summary);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("报告序列化失败", e);
        }
    }

}
