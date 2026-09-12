package com.bank.aml.evaluation;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.validation.AgentOutputValidator;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.RiskContext;
import com.bank.aml.evidence.LegalDoc;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 评测适配器：把案例 fixture 映射为冻结快照后复用生产 {@link AgentOutputValidator}。 */
@Component
public class AgentEvalSchemaValidator {

    private static final Pattern EVIDENCE_ID = Pattern.compile("(?i)evidenceId\\s*=\\s*([^\\]\\s,;，；]+)");

    private final AgentOutputValidator productionValidator;

    public AgentEvalSchemaValidator() {
        this(new AgentOutputValidator());
    }

    @Autowired
    public AgentEvalSchemaValidator(AgentOutputValidator productionValidator) {
        this.productionValidator = productionValidator;
    }

    public List<String> validate(AgentEvalDataset.AgentEvalCase evalCase, DueDiligenceReport report) {
        LinkedHashSet<String> violations = new LinkedHashSet<>(
                productionValidator.validate(snapshot(evalCase), report).violations());
        if (report != null) {
            if (!evalCase.input().customerId().equals(report.customerId())) {
                violations.add("CUSTOMER_ID_MISMATCH");
            }
            if (!normalize(evalCase.input().customerName()).equals(normalize(report.customerName()))) {
                violations.add("CUSTOMER_NAME_MISMATCH");
            }
        }
        return List.copyOf(violations);
    }

    InvestigationSnapshot snapshot(AgentEvalDataset.AgentEvalCase evalCase) {
        var facts = evalCase.toolFixture().riskFacts();
        RiskContext risk = new RiskContext(facts.maxSanctionSeverity(), facts.sanctionHit(), facts.crossBorderRatio(),
                facts.nightTransactionRatio(), facts.largeTransactionCount(), facts.transactionDataComplete(),
                facts.transactionRiskExplained(), facts.transactionPatternSeverity(), facts.uboRiskSeverity(), "低风险",
                1);
        List<LegalDoc> legalDocs = evidenceIds(evalCase.toolFixture().legalResult()).stream()
            // 必须保留 fixture 中的法规正文；否则高影响动作的确定性支持校验永远无法通过。
            .map(id -> new LegalDoc(id, "评测冻结法规", "EVAL", "", evalCase.toolFixture().legalResult()))
            .toList();
        var input = evalCase.input();
        CustomerProfile customer = new CustomerProfile(input.customerId(), input.customerName(), input.identityNumber(),
                input.customerType(), "", "", "");
        return new InvestigationSnapshot("eval-" + evalCase.id(), 0L, 1, asInstant(input.asOfDate()), customer,
                List.of(), List.of(), List.of(), legalDocs,
                evalCase.toolFixture()
                    .legalQueryTerms()
                    .stream()
                    .collect(Collectors.toMap(term -> term, term -> legalDocs, (left, right) -> left,
                            LinkedHashMap::new)),
                evalCase.toolFixture().legalQueryTerms(), risk, "eval-fixture", "eval");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    private List<String> evidenceIds(String text) {
        if (text == null)
            return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_ID.matcher(text);
        while (matcher.find())
            ids.add(matcher.group(1));
        return List.copyOf(ids);
    }

    private Instant asInstant(String value) {
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        catch (RuntimeException invalidDate) {
            return Instant.EPOCH;
        }
    }

}
