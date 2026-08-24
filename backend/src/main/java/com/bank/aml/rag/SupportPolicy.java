package com.bank.aml.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按问题类型的支持概率阈值（支持度判定边界）。 */
@Component
public class SupportPolicy {

    private final double regulationFactThreshold;
    private final double highRiskDisposalThreshold;
    private final double generalKnowledgeThreshold;

    public SupportPolicy(@Value("${aml.rag.support.thresholds.regulation-fact:0.70}") double regulationFactThreshold,
                         @Value("${aml.rag.support.thresholds.high-risk-disposal:0.85}") double highRiskDisposalThreshold,
                         @Value("${aml.rag.support.thresholds.general-knowledge:0.65}") double generalKnowledgeThreshold) {
        this.regulationFactThreshold = clamp(regulationFactThreshold);
        this.highRiskDisposalThreshold = clamp(highRiskDisposalThreshold);
        this.generalKnowledgeThreshold = clamp(generalKnowledgeThreshold);
    }

    public SupportPolicy() {
        this(0.70, 0.85, 0.65);
    }

    public double thresholdFor(LegalQueryAnalyzer.QueryIntent intent) {
        return switch (intent) {
            case REGULATION_FACT -> regulationFactThreshold;
            case HIGH_RISK_DISPOSAL -> highRiskDisposalThreshold;
            case GENERAL_KNOWLEDGE -> generalKnowledgeThreshold;
        };
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
