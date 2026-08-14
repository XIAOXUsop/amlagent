package com.bank.aml.controller;

import com.bank.aml.evaluation.AgentEvalDatasetLoader;
import com.bank.aml.evaluation.AgentEvalReport;
import com.bank.aml.evaluation.AgentEvalRunner;
import com.bank.aml.evaluation.EvalReportEntity;
import com.bank.aml.evaluation.EvalReportRepository;
import com.bank.aml.evaluation.RagEvaluator;
import com.bank.aml.evaluation.RuleRegressionEvaluator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 评测接口：规则回归 / RAG 检索质量 / 真实 Agent 评测状态 / 历史报告。
 */
@RestController
@RequestMapping("/api/eval")
@PreAuthorize("hasRole('ADMIN')")
public class EvaluationController {

    private final RagEvaluator ragEvaluator;
    private final RuleRegressionEvaluator ruleRegressionEvaluator;
    private final AgentEvalDatasetLoader agentEvalDatasetLoader;
    private final AgentEvalRunner agentEvalRunner;
    private final EvalReportRepository evalReportRepository;
    private final ObjectMapper objectMapper;

    public EvaluationController(RagEvaluator ragEvaluator, RuleRegressionEvaluator ruleRegressionEvaluator,
                                AgentEvalDatasetLoader agentEvalDatasetLoader,
                                AgentEvalRunner agentEvalRunner,
                                EvalReportRepository evalReportRepository,
                                ObjectMapper objectMapper) {
        this.ragEvaluator = ragEvaluator;
        this.ruleRegressionEvaluator = ruleRegressionEvaluator;
        this.agentEvalDatasetLoader = agentEvalDatasetLoader;
        this.agentEvalRunner = agentEvalRunner;
        this.evalReportRepository = evalReportRepository;
        this.objectMapper = objectMapper;
    }

    /** RAG 检索评测：Recall@5 / Top3 命中率 / MRR / P95 */
    @PostMapping("/rag")
    public RagEvaluator.RagEvalReport rag() {
        return ragEvaluator.evaluate();
    }

    /** 确定性规则回归：不调用 LLM，不代表真实 Agent 效果。 */
    @PostMapping("/rules")
    public RuleRegressionEvaluator.RuleRegressionReport rules() {
        RuleRegressionEvaluator.RuleRegressionReport report = ruleRegressionEvaluator.run();
        EvalReportEntity entity = new EvalReportEntity();
        entity.setEvalType("RULE_REGRESSION");
        entity.setVersionTag("default");
        entity.setMetricsJson(ruleRegressionEvaluator.metricsJson(report));
        evalReportRepository.save(entity);
        return report;
    }

    /** 真实 Agent 评测就绪状态；Mock/fallback 不会被视为可评测模型。 */
    @GetMapping("/agent/status")
    public AgentEvalRunner.Readiness agentStatus() {
        return agentEvalRunner.readiness();
    }

    /**
     * 运行真实模型 DEV 分片。每条案例使用隔离夹具；不经过规则报告 fallback，TEST 分片保持冻结。
     */
    @PostMapping("/agent/dev")
    public AgentEvalReport agentDev() {
        return persistIfCompleted(agentEvalRunner.runDev(), "AGENT_DEV");
    }

    /**
     * 运行冻结的隐藏 TEST 分片（最终评测）。标准答案冻结，仅返回聚合指标，不返回逐案例金标。
     * <p>需显式设置环境变量 {@code RUN_HIDDEN_AGENT_EVAL=true}，避免在普通 Web 页面反复调用 TEST 造成指标泄漏。
     */
    @PostMapping("/agent/test")
    public AgentEvalReport agentTest() {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("RUN_HIDDEN_AGENT_EVAL", "false"))) {
            throw new IllegalStateException("隐藏 TEST 评测需显式设置环境变量 RUN_HIDDEN_AGENT_EVAL=true");
        }
        AgentEvalReport report = persistIfCompleted(agentEvalRunner.runTest(), "AGENT_TEST");
        return report.aggregateOnly(); // 盲测：只返回聚合指标，不暴露逐案例 expectedRisk/requiredFindingCodes 金标
    }

    private AgentEvalReport persistIfCompleted(AgentEvalReport report, String evalType) {
        if ("COMPLETED".equals(report.runStatus()) || "COMPLETED_WITH_ERRORS".equals(report.runStatus())) {
            EvalReportEntity entity = new EvalReportEntity();
            entity.setEvalType(evalType);
            entity.setVersionTag(report.datasetVersion() + ":" + report.runtime().configuredModel());
            entity.setMetricsJson(writeJson(report.withoutSensitiveDetails()));
            evalReportRepository.save(entity);
        }
        return report;
    }

    /** 只返回数据集元信息，不向接口暴露冻结的 TEST 案例和标准答案。 */
    @GetMapping("/agent/dataset")
    public AgentEvalDatasetLoader.AgentEvalDatasetSummary agentDataset() {
        return agentEvalDatasetLoader.summary();
    }

    /** 历史评测报告 */
    @GetMapping("/reports")
    public List<EvalReportEntity> reports(
            @RequestParam(defaultValue = "RULE_REGRESSION") String evalType) {
        if (!List.of("RULE_REGRESSION", "AGENT_DEV", "AGENT_TEST").contains(evalType)) {
            throw new IllegalArgumentException("不支持的评测类型：" + evalType);
        }
        return evalReportRepository.findByEvalTypeOrderByCreatedAtDesc(evalType);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评测报告序列化失败", exception);
        }
    }
}
