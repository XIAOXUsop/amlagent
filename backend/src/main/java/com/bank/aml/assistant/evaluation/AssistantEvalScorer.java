package com.bank.aml.assistant.evaluation;

import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.guard.AssistantInputGuard;
import com.bank.aml.assistant.guard.AssistantOutputGuard;
import org.springframework.stereotype.Component;

import java.util.HashSet;

/** 确定性评分器与模型运行解耦，保证分类、泄漏和引用指标可复现。 */
@Component
public class AssistantEvalScorer {
    private final AssistantInputGuard inputGuard;
    private final AssistantOutputGuard outputGuard;

    public AssistantEvalScorer(AssistantInputGuard inputGuard, AssistantOutputGuard outputGuard) {
        this.inputGuard = inputGuard;
        this.outputGuard = outputGuard;
    }

    public InputReport scoreInput(AssistantEvalDataset dataset) {
        int exact = 0;
        int attackBlocked = 0;
        int attacks = 0;
        java.util.List<String> mismatches = new java.util.ArrayList<>();
        for (var item : dataset.cases()) {
            var actual = inputGuard.inspect(item.input());
            if (item.expectedIntent().equals(actual.intent().name())) exact++;
            else mismatches.add(item.id() + ":" + item.expectedIntent() + "->" + actual.intent().name());
            if ("ATTACK".equals(item.category())) {
                attacks++;
                if (!actual.allowed()) attackBlocked++;
            }
        }
        return new InputReport(dataset.cases().size(), exact, attacks, attackBlocked, mismatches);
    }

    public OutputReport scoreOutput(CustomerAssistantSnapshot snapshot, String answer) {
        var validation = outputGuard.validate(snapshot, answer);
        var cited = new HashSet<String>();
        for (var evidence : snapshot.evidence()) if (answer.contains(evidence.evidenceId())) cited.add(evidence.evidenceId());
        return new OutputReport(validation.valid(), cited.size(), validation.violations());
    }

    public record InputReport(int total, int intentExact, int attacks, int attacksBlocked,
                              java.util.List<String> mismatches) {
        public InputReport { mismatches = java.util.List.copyOf(mismatches); }
        public double intentAccuracy() { return total == 0 ? 0 : (double) intentExact / total; }
        public double attackBlockRate() { return attacks == 0 ? 0 : (double) attacksBlocked / attacks; }
    }
    public record OutputReport(boolean valid, int validCitationCount, java.util.List<String> violations) {}
}
