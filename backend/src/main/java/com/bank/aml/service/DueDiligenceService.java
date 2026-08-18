package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceAgentFactory;
import com.bank.aml.agent.DueDiligenceContext;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.InvestigationSnapshotFactory;
import com.bank.aml.agent.StreamingAnalysisAgent;
import com.bank.aml.agent.guardrail.GuardrailEngine;
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
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final InvestigationSnapshotFactory snapshotFactory;
    private final WorkflowEventService workflowEventService;
    private final CustomerDataPort dataSource;
    private final FaultInjector faultInjector;
    private final PromptInjectionGuard promptInjectionGuard;
    private final CostRouter costRouter;
    private final boolean ruleFallbackEnabled;
    private final boolean summaryEnabled;
    private final StreamingAnalysisAgent streamingAnalysisAgent;
    private final StreamingChatModel streamingChatModel;
    private final LegalKeywordResolver legalKeywordResolver;
    private final ObjectMapper objectMapper;
    private final ExecutorService summaryExecutor;
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
                               GuardrailEngine guardrailEngine, InvestigationSnapshotFactory snapshotFactory,
                               WorkflowEventService workflowEventService,
                               CustomerDataPort dataSource, FaultInjector faultInjector,
                               PromptInjectionGuard promptInjectionGuard, CostRouter costRouter,
                               @Value("${aml.cost-routing.rule-fallback-enabled:false}") boolean ruleFallbackEnabled,
                               @Value("${aml.cost-routing.summary-enabled:false}") boolean summaryEnabled,
                               StreamingAnalysisAgent streamingAnalysisAgent, StreamingChatModel streamingChatModel,
                               LegalKeywordResolver legalKeywordResolver, ObjectMapper objectMapper,
                               ExecutorService summaryExecutor,
                               ToolExecutionTraceRepository toolTraceRepository, LlmProperties llmProperties) {
        this.caseRepository = caseRepository;
        this.caseLogRepository = caseLogRepository;
        this.caseExecutionRepository = caseExecutionRepository;
        this.workflowCommandService = workflowCommandService;
        this.metrics = metrics;
        this.agentFactory = agentFactory;
        this.ruleReporter = ruleReporter;
        this.guardrailEngine = guardrailEngine;
        this.snapshotFactory = snapshotFactory;
        this.workflowEventService = workflowEventService;
        this.dataSource = dataSource;
        this.faultInjector = faultInjector;
        this.promptInjectionGuard = promptInjectionGuard;
        this.costRouter = costRouter;
        this.ruleFallbackEnabled = ruleFallbackEnabled;
        this.summaryEnabled = summaryEnabled;
        this.streamingAnalysisAgent = streamingAnalysisAgent;
        this.streamingChatModel = streamingChatModel;
        this.legalKeywordResolver = legalKeywordResolver;
        this.objectMapper = objectMapper;
        this.summaryExecutor = summaryExecutor;
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
            record(c, WorkflowStage.COLLECTING, "尽调快照已冻结 snapshotId=" + snapshot.snapshotId()
                    + " sourceDigest=" + snapshot.sourceDigest());

            DueDiligenceContext context = buildContext(snapshot, c);
            record(c, WorkflowStage.COLLECTING, "调用工具并行采集数据（交易画像 / 股权穿透 / 黑名单 / 法规检索）...");
            faultInjector.inject(WorkflowStage.COLLECTING); // 故障注入埋点（默认关闭）

            DueDiligenceReport report = null;
            String reportSource = "AGENT";
            if (route == CostRouter.Route.RULE_ONLY) {
                // 零 LLM：简单工单彻底跳过所有模型调用（含流式），由规则引擎兜底
                record(c, WorkflowStage.COLLECTING, "RULE_ONLY 路由，跳过所有模型调用（零 LLM 成本）");
            } else {
                // 主 Agent 使用 ObservedChatModel（purpose=main_agent 固定），无需 ThreadLocal
                DueDiligenceAgentFactory.AgentWithTools agentWithTools = null;
                try {
                    agentWithTools = agentFactory.createWithTraces(snapshot);
                    report = agentWithTools.agent().investigate(context.toPrompt());
                } catch (Exception e) {
                    log.warn("Agent 调用失败，进入规则降级 caseId={}: {}", caseId, e.getMessage());
                    report = null;
                } finally {
                    // Agent 异常/工具轮次耗尽时也保留已执行的部分工具轨迹
                    if (agentWithTools != null) {
                        persistToolTraces(c, snapshot, agentWithTools.tools().traces());
                    }
                }
            }

            // Agent 长调用期间可能被接管：租约丢失则停止后续阶段，不再写 Guardrail/报告/终态
            if (lease != null && !lease.isValid()) {
                log.warn("工单 {} Agent 调用后租约已丢失，跳过 Guardrail 与报告", caseId);
                return c;
            }

            // 3. 风险推理
            record(c, WorkflowStage.REASONING, "综合研判交易 / 股权 / 黑名单 / 法规证据，完成风险推理。");

            if (report == null || report.riskLevel() == null || report.riskLevel().isBlank()) {
                DueDiligenceReport fallback = ruleReporter.generate(snapshot.customer(), c.getAlertRule());
                if (fallback != null) {
                    report = fallback;
                    reportSource = "RULE_FALLBACK"; // 规则降级输出，不再是模型评级
                }
            }
            if (report == null) {
                throw new RetryableWorkflowException("尽调报告生成失败（LLM 与规则引擎均未产出结果）");
            }

            // 4. 规则护栏（基于与 Agent 相同的冻结快照，不二次读取数据源）
            record(c, WorkflowStage.GUARDRAIL, "执行规则护栏校验（制裁名单 / 评级一致性）...");
            String rawAgentRiskLevel = report.riskLevel(); // 模型原始评级（Guardrail 前）
            GuardrailEngine.GuardrailResult gr = guardrailEngine.apply(snapshot, report);
            gr.decision().triggeredRules().forEach(r -> record(c, WorkflowStage.GUARDRAIL,
                    "触发规则【" + r.ruleCode() + " v" + r.ruleVersion() + "】→ " + r.targetRiskLevel()
                            + "，动作 " + r.action() + "，证据：" + r.evidence()));
            gr.corrections().forEach(corr -> record(c, WorkflowStage.GUARDRAIL, corr));
            if (!gr.corrections().isEmpty()) {
                metrics.guardrailCorrection();
            }
            // 同步最终报告字段（风险评级 / 人工复核标志 / 处置代码）
            report = withGuardrailDecision(report, gr);
            if (!gr.finalRiskLevel().equals(rawAgentRiskLevel)) {
                record(c, WorkflowStage.GUARDRAIL, "评级由【" + rawAgentRiskLevel + "】修正为：【" + gr.finalRiskLevel() + "】");
            }
            c.setRiskLevel(gr.finalRiskLevel());
            c.setRawRiskLevel(rawAgentRiskLevel);
            c.setStatus(gr.mustEscalate() ? CaseStatus.HOLD : CaseStatus.DONE);
            if (gr.mustEscalate()) {
                metrics.caseHold();
                String escalateRules = gr.decision().triggeredRules().stream()
                        .filter(r -> "MANUAL_REVIEW".equals(r.action()))
                        .map(r -> r.ruleCode()).toList().toString();
                record(c, WorkflowStage.GUARDRAIL, "触发转人工（规则 " + escalateRules + "），工单进入人工复核队列。");
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
            c.setModelFallback(activeModel.typeEnum() == LlmProperties.ProviderType.MOCK || !activeModel.hasApiKey());
            record(c, WorkflowStage.REPORTING, "尽调初审报告已生成并归档，来源=" + reportSource
                    + "，模型=" + c.getModelProvider() + "/" + c.getModelName() + "，最终评级：" + c.getRiskLevel());
            record(c, WorkflowStage.DONE, "尽调完成。评级：" + c.getRiskLevel() + "，状态：" + c.getStatus());

            // 可选流式摘要：最终报告生成后异步启动，输入为脱敏后的最终报告（不含身份字段）
            if (route == CostRouter.Route.AGENT_WITH_SUMMARY) {
                streamAnalysis(c, reportSummary(report), lease);
            }
        } catch (NonRetryableWorkflowException e) {
            metrics.caseFailed();
            throw e;
        } catch (RetryableWorkflowException e) {
            metrics.caseFailed();
            throw e;
        } catch (Exception e) {
            metrics.caseFailed();
            throw new RetryableWorkflowException("未知异常", e);
        } finally {
            lastStageAt.remove();
            currentLease.remove();
        }
        // 终态原子落库：绑定 worker+executionVersion，被接管后的陈旧写入不生效（0 行更新被丢弃）
        int updated = caseRepository.finishCase(c.getId(), worker, executionVersion,
                c.getStatus(), c.getRiskLevel(), c.getRawRiskLevel(), c.getReportJson(), c.getSummary(),
                c.getReportSource(), c.getSnapshotId(),
                c.getModelProvider(), c.getModelName(), c.isModelFallback());
        if (updated == 0) {
            log.warn("工单 {} 终态落库被丢弃（已被接管，worker/version 不匹配）", caseId);
        } else {
            // 落库成功且仍持有租约：推 DONE 终端事件并结束 SSE，让前端明确"流已结束"
            if (lease == null || lease.isValid()) {
                workflowEventService.complete(caseId);
            }
        }
        return c;
    }

    public List<CaseEntity> listCases() {
        return caseRepository.findAllByOrderByCreatedAtDesc();
    }

    public org.springframework.data.domain.Page<CaseEntity> listCasesPageable(org.springframework.data.domain.Pageable pageable) {
        return caseRepository.findAllByOrderByCreatedAtDesc(pageable);
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
        List<String> legalKeywords = legalKeywordResolver.resolve(c.getAlertRule());
        return new DueDiligenceContext(
                c.getId(), customer.id(), customer.name(), customer.idCard(), asOfDate, legalKeywords,
                c.getAlertRule(), "请对该客户开展高风险客户尽职调查。");
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

    /** 流式摘要（仅 AGENT_WITH_SUMMARY 路由启用）：异步生成，不阻塞 Worker 主流程 */
    private void streamAnalysis(CaseEntity c, String description, ExecutionLease lease) {
        if (streamingChatModel instanceof DisabledStreamingChatModel) {
            return;
        }
        // 有界线程池异步执行，避免阻塞 Worker / 占用公共 ForkJoinPool；摘要失败不影响主工单成功
        summaryExecutor.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);
                streamingAnalysisAgent.streamAnalysis(description)
                        .onPartialResponse(token -> {
                            sb.append(token);
                            // 租约丢失后停止推送 Token，旧 Worker 不再产生可见副作用
                            if (lease == null || lease.isValid()) {
                                workflowEventService.emitToken(c.getId(), token);
                            }
                        })
                        .onCompleteResponse(r -> latch.countDown())
                        .onError(e -> {
                            log.warn("流式摘要失败：{}", e.getMessage());
                            latch.countDown();
                        })
                        .start();
                latch.await(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("流式摘要异常，跳过：{}", e.getMessage());
            }
        });
    }

    /** 生成脱敏后的最终报告摘要，作为可选流式摘要的输入（不含身份字段，且明确约束不得修改结论） */
    private String reportSummary(DueDiligenceReport report) {
        return """
                以下是已完成的尽调最终报告摘要，请用自然语言简要概括并补充分析建议。
                你不得修改风险等级、事实或处置动作，只能解释与总结：
                - 最终风险评级：%s
                - 风险发现代码：%s
                - 处置代码：%s
                - 结论：%s
                """.formatted(
                report.riskLevel(),
                report.findingCodes() == null ? "" : String.join("、", report.findingCodes()),
                report.actionCodes() == null ? "" : String.join("、", report.actionCodes()),
                report.conclusion());
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("报告序列化失败", e);
        }
    }

    /** record 副本：同步 Guardrail 最终决策（风险评级 / 人工复核标志 / 处置代码） */
    private DueDiligenceReport withGuardrailDecision(DueDiligenceReport r, GuardrailEngine.GuardrailResult gr) {
        return new DueDiligenceReport(r.customerId(), r.customerName(), gr.finalRiskLevel(),
                r.transactionProfile(), r.corporateProfile(), r.sanctions(), r.legalBasis(),
                r.riskPoints(), r.conclusion(), r.evidenceChain(), gr.mustEscalate(),
                r.findingCodes(), gr.decision().actionCodes());
    }
}
