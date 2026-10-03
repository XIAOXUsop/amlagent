package com.bank.aml.evaluation;

import com.bank.aml.TestClocks;
import com.bank.aml.agent.AgentAnalysis;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import com.bank.aml.risk.RiskRuleSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Synthetic reference outputs verify contracts only, never model quality. */
final class AgentEvalTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEvalTestSupport() {
    }

    static AgentEvalDataset dataset(String resource) {
        try (InputStream input = AgentEvalTestSupport.class.getClassLoader().getResourceAsStream(resource)) {
            return MAPPER.readValue(Objects.requireNonNull(input, resource), AgentEvalDataset.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Cannot read test dataset " + resource, exception);
        }
    }

    static DueDiligenceReport referenceReport(AgentEvalDataset.AgentEvalCase evalCase) {
        List<String> ids = new AgentEvalSchemaValidator().snapshot(evalCase)
            .legalEvidence()
            .stream()
            .map(doc -> doc.evidenceId())
            .toList();
        return new DueDiligenceReport(evalCase.input().customerId(), evalCase.input().customerName(),
                evalCase.expected().riskLevel(), "依据取得的流水评估，缺失期间保持未知。", "依据截至日现时控制证据评估，历史信息不冒充现时事实。", List.of(), ids,
                List.of("区分已核验事实、线索与缺失资料。"), "按冻结证据执行所需尽调及人工处置。", ids, evalCase.expected().mustEscalate(),
                evalCase.expected().requiredFindingCodes(), evalCase.expected().requiredActions());
    }

    static AgentAnalysis analysis(DueDiligenceReport report) {
        return new AgentAnalysis(report.riskLevel(), report.transactionProfile(), report.corporateProfile(),
                report.sanctions(), report.legalBasis(), report.riskPoints(), report.conclusion(),
                report.evidenceChain(), report.manualReviewRequired(), report.findingCodes(), report.actionCodes());
    }

    static GuardrailEngine seededGuardrails() {
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
        RiskRuleEngine engine = new RiskRuleEngine(repository, TestClocks.FIXED);
        new RiskRuleSeeder(repository, engine).run(null);
        return new GuardrailEngine(mock(RiskFactAssembler.class), engine);
    }

}
