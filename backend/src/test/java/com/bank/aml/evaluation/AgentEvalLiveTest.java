package com.bank.aml.evaluation;

import com.bank.aml.TestClocks;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.config.LlmProviderProperties;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import com.bank.aml.risk.RiskRuleSeeder;
import com.bank.aml.service.FinalDecisionAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explicit, real-model DEV evaluation without MySQL, Redis or PGVector.
 *
 * <p>
 * It is skipped during normal builds. Run it only with {@code RUN_LIVE_AGENT_EVAL=true}
 * and {@code DEEPSEEK_API_KEY} in the process environment. The persisted report is
 * redacted and placed under {@code target/agent-eval}.
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_AGENT_EVAL", matches = "(?i)true")
class AgentEvalLiveTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentEvalLiveTest.class);

    private static final String MODEL = "deepseek-v4-flash";

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void runsFrozenDevSplitAgainstRealDeepSeekAndWritesRedactedReport() throws Exception {
        String apiKey = requiredEnvironment("DEEPSEEK_API_KEY");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.deepseek.com")
            .modelName(MODEL)
            .temperature(0.0)
            .timeout(Duration.ofMinutes(3))
            .parallelToolCalls(true)
            .customParameters(Map.of("thinking", Map.of("type", "disabled")))
            .build();

        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setType("openai-compatible");
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setApiKey(apiKey);
        provider.setModelName(MODEL);
        provider.setTemperature(0.0);
        LlmProperties properties = new LlmProperties();
        properties.setActiveProvider("deepseek");
        properties.setProviders(Map.of("deepseek", provider));

        RiskRuleRepository repository = inMemoryManagedRuleRepository();
        RiskRuleEngine ruleEngine = new RiskRuleEngine(repository, TestClocks.FIXED);
        new RiskRuleSeeder(repository, ruleEngine).run(null);
        GuardrailEngine guardrailEngine = new GuardrailEngine(new RiskFactAssembler(new MockDataSource()), ruleEngine);

        AgentEvalRunner runner = new AgentEvalRunner(chatModel, properties, new AgentEvalDatasetLoader(objectMapper),
                new AgentEvalSchemaValidator(), new AgentEvalScorer(), guardrailEngine, new FinalDecisionAssembler(),
                new ForbiddenClaimDetectorRegistry(), TestClocks.FIXED, com.bank.aml.TestProperties.aml());

        AgentEvalReport report = runner.runDev();
        Path reportPath = reportPath(report);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report.withoutSensitiveDetails());

        LOGGER.info(
                "LIVE_AGENT_EVAL report={} status={} attempted={} scored={} taskPass={} strict={} "
                        + "rawRisk={} finalRisk={} p95Ms={} tokens={}",
                reportPath.toAbsolutePath(), report.runStatus(), report.attempted(), report.scored(),
                report.taskPassRate().value(), report.strictPassRate().value(),
                report.rawRisk().exactAccuracy().value(), report.finalRisk().exactAccuracy().value(),
                report.latency().p95Ms(), report.tokens().totalTokens());

        assertThat(report.runtime().realModel()).isTrue();
        assertThat(report.runtime().fallbackUsed()).isFalse();
        assertThat(report.runtime().configuredModel()).isEqualTo(MODEL);
        assertThat(report.attempted()).isEqualTo(9);
        assertThat(report.runStatus()).isIn("COMPLETED", "COMPLETED_WITH_ERRORS");
        assertThat(reportPath).isRegularFile();
    }

    private RiskRuleRepository inMemoryManagedRuleRepository() {
        Map<String, RiskRule> rules = new LinkedHashMap<>();
        RiskRuleRepository repository = mock(RiskRuleRepository.class);
        when(repository.findByRuleCode(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(rules.get(invocation.getArgument(0))));
        when(repository.save(any(RiskRule.class))).thenAnswer(invocation -> {
            RiskRule rule = invocation.getArgument(0);
            rules.put(rule.getRuleCode(), rule);
            return rule;
        });
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenAnswer(invocation -> rules.values()
            .stream()
            .filter(RiskRule::isEnabled)
            .sorted(Comparator.comparingInt(RiskRule::getPriority))
            .toList());
        return repository;
    }

    private Path reportPath(AgentEvalReport report) throws Exception {
        Path directory = Path.of("target", "agent-eval");
        Files.createDirectories(directory);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(report.startedAt());
        return directory.resolve("agent-dev-%s-%s.json".formatted(report.datasetVersion(), timestamp));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the live Agent evaluation");
        }
        return value;
    }

}
