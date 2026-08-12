package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceAgent;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.RetryableWorkflowException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.entity.CaseLogEntity;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.messaging.OutboxService;
import com.bank.aml.observability.MetricsRecorder;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecution.ExecutionStatus;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private final OutboxService outboxService;
    private final MetricsRecorder metrics;
    private final DueDiligenceAgent agent;
    private final RuleBasedReporter ruleReporter;
    private final GuardrailEngine guardrailEngine;
    private final WorkflowEventService workflowEventService;
    private final MockDataSource dataSource;
    private final ObjectMapper objectMapper;

    /** 阶段耗时测量（每工单独立） */
    private final ThreadLocal<LocalDateTime> lastStageAt = new ThreadLocal<>();

    public DueDiligenceService(CaseRepository caseRepository, CaseLogRepository caseLogRepository,
                               CaseExecutionRepository caseExecutionRepository, OutboxService outboxService,
                               MetricsRecorder metrics,
                               DueDiligenceAgent agent, RuleBasedReporter ruleReporter,
                               GuardrailEngine guardrailEngine, WorkflowEventService workflowEventService,
                               MockDataSource dataSource, ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.caseLogRepository = caseLogRepository;
        this.caseExecutionRepository = caseExecutionRepository;
        this.outboxService = outboxService;
        this.metrics = metrics;
        this.agent = agent;
        this.ruleReporter = ruleReporter;
        this.guardrailEngine = guardrailEngine;
        this.workflowEventService = workflowEventService;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /** 创建预警工单；autoProcess=true 时与工单同事务写入 Outbox，自动触发尽调 */
    @Transactional
    public CaseEntity createCase(String customerId, String alertRule, boolean autoProcess) {
        MockDataSource.Customer customer = dataSource.findCustomer(customerId)
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

    /** 写入 Outbox（事务提交后调用），由发布器投递到 Redis Streams */
    public void enqueue(Long caseId) {
        outboxService.record(caseId);
    }

    /** 直接异步触发（仅测试/手动触发使用） */
    public void trigger(Long caseId) {
        CompletableFuture.runAsync(() -> {
            try {
                process(caseId);
            } catch (Exception e) {
                log.error("直接触发执行异常 caseId={}", caseId, e);
            }
        });
    }

    /** 人工重试：重置为 PENDING 并重新写入 Outbox 入队 */
    @Transactional
    public CaseEntity retry(Long caseId) {
        CaseEntity c = getCase(caseId);
        c.setStatus(CaseStatus.PENDING);
        c.setLockedBy(null);
        c.setLockedAt(null);
        c.setFailureCode(null);
        c.setFailureMessage(null);
        caseRepository.save(c);
        enqueue(caseId);
        return c;
    }

    /**
     * 执行尽调工作流（由 Worker 消费任务后调用）。
     * 正常完成置 DONE/HOLD；失败按异常类型抛出（重试/不可重试），由 Worker 决定重试策略。
     */
    public CaseEntity process(Long caseId) {
        lastStageAt.remove();
        CaseEntity c = caseRepository.findById(caseId)
                .orElseThrow(() -> new NonRetryableWorkflowException("工单不存在：" + caseId));
        c.setStatus(CaseStatus.RUNNING);
        c.setFailureCode(null);
        c.setFailureMessage(null);
        caseRepository.save(c);

        try {
            // 1. 任务规划
            record(c, WorkflowStage.PLANNING, "解析预警工单，拆解子任务：\n" + String.join("\n", planTasks(c.getAlertRule())));

            // 2. 数据采集（工具调用在 Agent 内部完成）
            String description = buildDescription(c);
            record(c, WorkflowStage.COLLECTING, "调用工具并行采集数据（交易画像 / 股权穿透 / 黑名单 / 法规检索）...");

            DueDiligenceReport report;
            try {
                report = agent.investigate(description);
            } catch (Exception e) {
                log.warn("Agent 调用失败，进入规则降级 caseId={}: {}", caseId, e.getMessage());
                report = null;
            }

            // 3. 风险推理
            record(c, WorkflowStage.REASONING, "综合研判交易 / 股权 / 黑名单 / 法规证据，完成风险推理。");

            if (report == null || report.riskLevel() == null || report.riskLevel().isBlank()) {
                DueDiligenceReport fallback = dataSource.findCustomer(c.getCustomerId())
                        .map(customer -> ruleReporter.generate(customer, c.getAlertRule()))
                        .orElse(null);
                if (fallback != null) {
                    report = fallback;
                }
            }
            if (report == null) {
                throw new RetryableWorkflowException("尽调报告生成失败（LLM 与规则引擎均未产出结果）");
            }

            // 4. 规则护栏
            record(c, WorkflowStage.GUARDRAIL, "执行规则护栏校验（制裁名单 / 评级一致性）...");
            MockDataSource.Customer customer = dataSource.findCustomer(c.getCustomerId()).orElse(null);
            if (customer != null) {
                GuardrailEngine.GuardrailResult gr = guardrailEngine.apply(customer, report);
                gr.decision().triggeredRules().forEach(r -> record(c, WorkflowStage.GUARDRAIL,
                        "触发规则【" + r.ruleCode() + " v" + r.ruleVersion() + "】→ " + r.targetRiskLevel()
                                + "，动作 " + r.action() + "，证据：" + r.evidence()));
                gr.corrections().forEach(corr -> record(c, WorkflowStage.GUARDRAIL, corr));
                if (!gr.corrections().isEmpty()) {
                    metrics.guardrailCorrection();
                }
                if (!gr.finalRiskLevel().equals(report.riskLevel())) {
                    report = withRiskLevel(report, gr.finalRiskLevel());
                    record(c, WorkflowStage.GUARDRAIL, "评级修正为：【" + gr.finalRiskLevel() + "】");
                }
                c.setRiskLevel(gr.finalRiskLevel());
                c.setStatus(gr.mustEscalate() ? CaseStatus.HOLD : CaseStatus.DONE);
                if (gr.mustEscalate()) {
                    metrics.caseHold();
                    record(c, WorkflowStage.GUARDRAIL, "触发转人工：命中一级制裁名单，工单进入人工复核队列。");
                }
            } else {
                c.setRiskLevel(report.riskLevel());
                c.setStatus(CaseStatus.DONE);
            }

            // 5. 报告生成与落库
            c.setReportJson(writeJson(report));
            c.setSummary(report.conclusion());
            record(c, WorkflowStage.REPORTING, "尽调初审报告已生成并归档，最终评级：" + c.getRiskLevel());
            record(c, WorkflowStage.DONE, "尽调完成。评级：" + c.getRiskLevel() + "，状态：" + c.getStatus());
        } catch (NonRetryableWorkflowException e) {
            metrics.caseFailed();
            c.setFailureMessage(e.getMessage());
            caseRepository.save(c);
            throw e;
        } catch (RetryableWorkflowException e) {
            metrics.caseFailed();
            c.setFailureMessage(e.getMessage());
            caseRepository.save(c);
            throw e;
        } catch (Exception e) {
            metrics.caseFailed();
            c.setFailureMessage(e.getMessage());
            caseRepository.save(c);
            throw new RetryableWorkflowException("未知异常", e);
        } finally {
            lastStageAt.remove();
        }
        return caseRepository.save(c);
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

    private String buildDescription(CaseEntity c) {
        return "客户编号：" + c.getCustomerId()
                + "，客户名称：" + c.getCustomerName()
                + "。预警规则：" + c.getAlertRule()
                + "。请对该客户开展高风险客户尽职调查。";
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
        metrics.recordStageDuration(durationMs);
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

    /** record 副本：替换风险评级 */
    private DueDiligenceReport withRiskLevel(DueDiligenceReport r, String riskLevel) {
        return new DueDiligenceReport(r.customerId(), r.customerName(), riskLevel,
                r.transactionProfile(), r.corporateProfile(), r.sanctions(), r.legalBasis(),
                r.riskPoints(), r.conclusion(), r.evidenceChain());
    }
}
