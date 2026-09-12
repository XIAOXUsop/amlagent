package com.bank.aml.evaluation;

import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 评测报告、冻结运行和规则指纹的持久化应用服务。 */
@Service
public class EvaluationReportService {

    private final EvalReportRepository reports;

    private final EvalFreezeRunRepository freezeRuns;

    private final RiskRuleRepository riskRules;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public EvaluationReportService(EvalReportRepository reports, EvalFreezeRunRepository freezeRuns,
            RiskRuleRepository riskRules, ObjectMapper objectMapper, Clock clock) {
        this.reports = reports;
        this.freezeRuns = freezeRuns;
        this.riskRules = riskRules;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void saveRuleReport(String metricsJson) {
        saveReport("RULE_REGRESSION", "default", metricsJson);
    }

    @Transactional
    public AgentEvalReport persistAgentIfCompleted(AgentEvalReport report, String evalType) {
        if ("COMPLETED".equals(report.runStatus()) || "COMPLETED_WITH_ERRORS".equals(report.runStatus())) {
            saveReport(evalType, report.datasetVersion() + ":" + report.runtime().configuredModel(),
                    writeJson(report.withoutSensitiveDetails()));
        }
        return report;
    }

    @Transactional
    public EvalFreezeRun beginFreeze(String freezeId) {
        EvalFreezeRun run = new EvalFreezeRun();
        run.setFreezeId(freezeId);
        run.setStatus("RUNNING");
        run.setStartedAt(now());
        try {
            return freezeRuns.saveAndFlush(run);
        }
        catch (DataIntegrityViolationException duplicateFreeze) {
            throw new IllegalStateException("该 freezeId 已运行过，同一冻结基线不能重复执行 TEST", duplicateFreeze);
        }
    }

    @Transactional
    public void completeFreeze(EvalFreezeRun run, AgentEvalReport report) {
        run.setStatus(report.runStatus());
        run.setRunId(report.runId());
        run.setAggregateJson(writeJson(report));
        run.setCompletedAt(now());
        freezeRuns.save(run);
    }

    @Transactional
    public void failFreeze(EvalFreezeRun run) {
        run.setStatus("CONSUMED_FAILED");
        run.setCompletedAt(now());
        freezeRuns.save(run);
    }

    @Transactional(readOnly = true)
    public String ruleSetHash() {
        List<RiskRule> rules = riskRules.findAll()
            .stream()
            .sorted(Comparator.comparing(RiskRule::getRuleCode).thenComparingInt(RiskRule::getVersion))
            .toList();
        StringBuilder canonicalRules = new StringBuilder();
        for (RiskRule rule : rules) {
            canonicalRules.append(rule.getRuleCode())
                .append('|')
                .append(rule.getVersion())
                .append('|')
                .append(rule.getConditionExpression())
                .append('|')
                .append(rule.getTargetRiskLevel())
                .append('|')
                .append(rule.getAction())
                .append('\n');
        }
        return sha256(canonicalRules.toString());
    }

    @Transactional(readOnly = true)
    public List<EvalReportView> reports(String evalType) {
        return reports.findByEvalTypeOrderByCreatedAtDesc(evalType)
            .stream()
            .map(report -> new EvalReportView(report.getId(), report.getEvalType(), report.getVersionTag(),
                    report.getMetricsJson(), toInstant(report.getCreatedAt())))
            .toList();
    }

    public String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException unavailableAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", unavailableAlgorithm);
        }
    }

    private void saveReport(String evalType, String versionTag, String metricsJson) {
        EvalReportEntity entity = new EvalReportEntity();
        entity.setEvalType(evalType);
        entity.setVersionTag(versionTag);
        entity.setMetricsJson(metricsJson);
        reports.save(entity);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("评测报告序列化失败", serializationFailure);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record EvalReportView(Long id, String evalType, String versionTag, String metricsJson, Instant createdAt) {
    }

}
