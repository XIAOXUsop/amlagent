package com.bank.aml.controller;

import com.bank.aml.config.LlmProperties;
import com.bank.aml.config.LlmProviderProperties;
import com.bank.aml.evaluation.AgentEvalDatasetLoader;
import com.bank.aml.evaluation.AgentEvalReport;
import com.bank.aml.evaluation.AgentEvalRunner;
import com.bank.aml.evaluation.EvalFreezeManifest;
import com.bank.aml.evaluation.EvalFreezeRun;
import com.bank.aml.evaluation.EvalFreezeRunRepository;
import com.bank.aml.evaluation.EvalReportEntity;
import com.bank.aml.evaluation.EvalReportRepository;
import com.bank.aml.evaluation.RagEvaluator;
import com.bank.aml.evaluation.RuleRegressionEvaluator;
import com.bank.aml.evaluation.RetrievalPipeline;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;

/**
 * 评测接口：规则回归 / RAG 检索质量 / 真实 Agent 评测状态 / 历史报告。
 */
@RestController
@RequestMapping("/api/eval")
@PreAuthorize("hasRole('ADMIN')")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final RagEvaluator ragEvaluator;
    private final RuleRegressionEvaluator ruleRegressionEvaluator;
    private final AgentEvalDatasetLoader agentEvalDatasetLoader;
    private final AgentEvalRunner agentEvalRunner;
    private final EvalReportRepository evalReportRepository;
    private final EvalFreezeRunRepository evalFreezeRunRepository;
    private final ObjectMapper objectMapper;
    private final RiskRuleRepository riskRuleRepository;
    private final LlmProperties llmProperties;
    private final String legalIndexVersion;

    public EvaluationController(RagEvaluator ragEvaluator, RuleRegressionEvaluator ruleRegressionEvaluator,
                                AgentEvalDatasetLoader agentEvalDatasetLoader,
                                AgentEvalRunner agentEvalRunner,
                                EvalReportRepository evalReportRepository,
                                EvalFreezeRunRepository evalFreezeRunRepository,
                                ObjectMapper objectMapper,
                                RiskRuleRepository riskRuleRepository,
                                LlmProperties llmProperties,
                                @Value("${aml.rag.legal-index-version:v1}") String legalIndexVersion) {
        this.ragEvaluator = ragEvaluator;
        this.ruleRegressionEvaluator = ruleRegressionEvaluator;
        this.agentEvalDatasetLoader = agentEvalDatasetLoader;
        this.agentEvalRunner = agentEvalRunner;
        this.evalReportRepository = evalReportRepository;
        this.evalFreezeRunRepository = evalFreezeRunRepository;
        this.riskRuleRepository = riskRuleRepository;
        this.llmProperties = llmProperties;
        this.legalIndexVersion = legalIndexVersion;
        this.objectMapper = objectMapper;
    }

    /** RAG 检索评测：Recall@5 / Top3 命中率 / MRR / P95 */
    @PostMapping("/rag")
    public RagEvaluator.RagEvalReport rag() {
        return ragEvaluator.evaluate();
    }

    /** RAG 检索管线 A/B 评测：对比 dense/lexical/hybrid/hybrid+rerank（当前 effective 索引）。 */
    @PostMapping("/rag/pipeline")
    public RagEvaluator.RagEvalReport ragPipeline(@RequestParam(defaultValue = "HYBRID_RERANK") String name) {
        try {
            return ragEvaluator.evaluatePipeline(RetrievalPipeline.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException illegalPipeline) {
            throw new IllegalArgumentException("未知检索管线：" + name
                    + "（可选：DENSE/LEXICAL/HYBRID/HYBRID_RERANK）");
        }
    }

    /** 对抗性评测集回归（>=150 条、16 类：越权/失效/投毒/伪造来源/敏感泄漏等 OWASP GenAI 风险）。 */
    @PostMapping("/rag/adversarial")
    public RagEvaluator.RagEvalReport ragAdversarial() {
        return ragEvaluator.evaluateAdversarial();
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

    /** 单案例 DEV 重放：用于修复后验证，避免每次定位问题都消耗整套评测 Token。 */
    @PostMapping("/agent/dev/case")
    public AgentEvalReport agentDevCase(@RequestParam String caseId) {
        return agentEvalRunner.runDevCase(caseId);
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
        String commitSha = System.getenv("BUILD_GIT_SHA");
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalStateException("隐藏 TEST 评测需通过环境变量 BUILD_GIT_SHA 注入当前 commit SHA");
        }
        EvalFreezeManifest manifest = buildManifest(commitSha);
        log.info("隐藏 TEST 冻结清单：freezeId={} provider={} model={} temperature={} legalIndex={} ruleSetHash={}",
                manifest.freezeId(), manifest.provider(), manifest.model(), manifest.temperature(),
                manifest.legalIndexVersion(), manifest.ruleSetHash());
        String freezeId = manifest.freezeId();
        EvalFreezeRun run = new EvalFreezeRun();
        run.setFreezeId(freezeId);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        try {
            evalFreezeRunRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("该 freezeId 已运行过，同一冻结基线不能重复执行 TEST");
        }

        try {
            // 先脱敏为聚合版，再持久化，避免数据库写入逐案例金标
            AgentEvalReport aggregate = agentEvalRunner.runTest().aggregateOnly();
            persistIfCompleted(aggregate, "AGENT_TEST");
            run.setStatus(aggregate.runStatus());
            run.setRunId(aggregate.runId());
            run.setAggregateJson(writeJson(aggregate));
            run.setCompletedAt(LocalDateTime.now());
            return aggregate; // 盲测：只返回聚合指标，不暴露逐案例 expectedRisk/requiredFindingCodes 金标
        } catch (Exception e) {
            // 模型调用异常不应让记录永久停留在 RUNNING
            run.setStatus("CONSUMED_FAILED");
            run.setCompletedAt(LocalDateTime.now());
            log.error("隐藏 TEST 执行失败，freezeId={}", freezeId, e);
            throw e;
        } finally {
            evalFreezeRunRepository.save(run);
        }
    }

    /** 构建冻结清单，freezeId = sha256(commit + dataset + prompt + rules + model + temperature + legalIndex) */
    private EvalFreezeManifest buildManifest(String commitSha) {
        String datasetHash = agentEvalDatasetLoader.datasetHash();
        String ruleSetHash = computeRuleSetHash();
        LlmProviderProperties active = llmProperties.active();
        String provider = llmProperties.getActiveProvider();
        String model = active.getModelName();
        Double temperature = active.getTemperature();
        String raw = commitSha + "|" + datasetHash + "|" + AgentEvalRunner.PROMPT_VERSION
                + "|" + ruleSetHash + "|" + model + "|" + temperature + "|" + legalIndexVersion;
        String freezeId = sha256(raw).substring(0, 32);
        var summary = agentEvalDatasetLoader.summary();
        return new EvalFreezeManifest(freezeId, commitSha, summary.datasetId(), summary.version(),
                datasetHash, AgentEvalRunner.PROMPT_VERSION, ruleSetHash, legalIndexVersion,
                provider, model, temperature, Instant.now());
    }

    private String computeRuleSetHash() {
        List<RiskRule> rules = riskRuleRepository.findAll().stream()
                .sorted(Comparator.comparing(RiskRule::getRuleCode)
                        .thenComparingInt(RiskRule::getVersion))
                .toList();
        StringBuilder sb = new StringBuilder();
        for (RiskRule r : rules) {
            sb.append(r.getRuleCode()).append('|').append(r.getVersion()).append('|')
                    .append(r.getConditionExpression()).append('|').append(r.getTargetRiskLevel())
                    .append('|').append(r.getAction()).append('\n');
        }
        return sha256(sb.toString());
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private AgentEvalReport persistIfCompleted(AgentEvalReport report, String evalType) {
        if ("COMPLETED".equals(report.runStatus()) || "COMPLETED_WITH_ERRORS".equals(report.runStatus())) {
            EvalReportEntity entity = new EvalReportEntity();
            entity.setEvalType(evalType);
            entity.setVersionTag(report.datasetVersion() + ":" + report.runtime().configuredModel());
            // aggregateOnly 已清空 cases，withoutSensitiveDetails 进一步脱敏身份/模型原文
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
