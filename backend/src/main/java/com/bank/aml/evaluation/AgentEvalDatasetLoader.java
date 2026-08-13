package com.bank.aml.evaluation;

import com.bank.aml.evaluation.AgentEvalDataset.AgentEvalCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 加载并校验版本化的独立 Agent 评测案例集。 */
@Component
public class AgentEvalDatasetLoader {

    static final String DATASET_RESOURCE = "evaluation/agent-cases-v1.json";
    private static final Set<String> ALLOWED_SPLITS = Set.of("DEV", "TEST");
    private static final Set<String> ALLOWED_DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Set<String> ALLOWED_RISK_LEVELS = Set.of("低风险", "中风险", "高风险");
    private static final Set<String> ALLOWED_REVIEW_STATUS =
            Set.of("PENDING_DOMAIN_REVIEW", "DOMAIN_REVIEWED");
    private static final Set<String> KNOWN_TOOLS =
            Set.of("transactionProfile", "corporateProfile", "checkSanctions", "searchLegal");

    private final ObjectMapper objectMapper;
    private volatile AgentEvalDataset cached;

    public AgentEvalDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void validateOnStartup() {
        load();
    }

    public AgentEvalDataset load() {
        AgentEvalDataset current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = readDataset();
            }
            return cached;
        }
    }

    public AgentEvalDatasetSummary summary() {
        AgentEvalDataset dataset = load();
        return new AgentEvalDatasetSummary(
                dataset.datasetId(),
                dataset.version(),
                dataset.sourceType(),
                dataset.annotationMethod(),
                dataset.reviewStatus(),
                dataset.cases().size(),
                counts(dataset.cases(), AgentEvalCase::split),
                counts(dataset.cases(), AgentEvalCase::scenario),
                counts(dataset.cases(), c -> c.expected().riskLevel())
        );
    }

    private AgentEvalDataset readDataset() {
        ClassPathResource resource = new ClassPathResource(DATASET_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            AgentEvalDataset dataset = objectMapper.readValue(input, AgentEvalDataset.class);
            validate(dataset);
            return dataset;
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 Agent 评测数据集：" + DATASET_RESOURCE, e);
        }
    }

    void validate(AgentEvalDataset dataset) {
        require(dataset != null, "数据集不能为空");
        requireText(dataset.datasetId(), "datasetId");
        requireText(dataset.version(), "version");
        requireText(dataset.description(), "description");
        requireText(dataset.sourceType(), "sourceType");
        requireText(dataset.annotationMethod(), "annotationMethod");
        require(ALLOWED_REVIEW_STATUS.contains(dataset.reviewStatus()), "数据集 reviewStatus 非法");
        require(dataset.cases() != null && dataset.cases().size() >= 12, "独立案例不得少于 12 条");

        Set<String> ids = new HashSet<>();
        Set<String> splits = new HashSet<>();
        for (AgentEvalCase evalCase : dataset.cases()) {
            require(evalCase != null, "案例不能为空");
            requireText(evalCase.id(), "case.id");
            require(ids.add(evalCase.id()), "案例 ID 重复：" + evalCase.id());
            require(!evalCase.id().startsWith("RULE-"), "Agent 案例不得复用规则回归 ID：" + evalCase.id());
            require(ALLOWED_SPLITS.contains(evalCase.split()), "案例 split 非法：" + evalCase.id());
            splits.add(evalCase.split());
            requireText(evalCase.scenario(), "case.scenario");
            require(ALLOWED_DIFFICULTIES.contains(evalCase.difficulty()), "案例 difficulty 非法：" + evalCase.id());

            validateInput(evalCase);
            validateFixture(evalCase);
            validateExpected(evalCase);
            validateAnnotation(evalCase);
        }
        require(splits.containsAll(ALLOWED_SPLITS), "数据集必须同时包含 DEV 和 TEST 分片");
    }

    private void validateInput(AgentEvalCase evalCase) {
        require(evalCase.input() != null, "案例 input 不能为空：" + evalCase.id());
        requireText(evalCase.input().customerId(), "input.customerId");
        requireText(evalCase.input().customerName(), "input.customerName");
        requireText(evalCase.input().identityNumber(), "input.identityNumber");
        requireText(evalCase.input().customerType(), "input.customerType");
        requireText(evalCase.input().asOfDate(), "input.asOfDate");
        requireText(evalCase.input().alertDescription(), "input.alertDescription");
        requireText(evalCase.input().caseDescription(), "input.caseDescription");
    }

    private void validateFixture(AgentEvalCase evalCase) {
        require(evalCase.toolFixture() != null, "案例 toolFixture 不能为空：" + evalCase.id());
        requireText(evalCase.toolFixture().transactionResult(), "toolFixture.transactionResult");
        requireText(evalCase.toolFixture().corporateResult(), "toolFixture.corporateResult");
        requireText(evalCase.toolFixture().sanctionResult(), "toolFixture.sanctionResult");
        requireText(evalCase.toolFixture().legalQuery(), "toolFixture.legalQuery");
        requireList(evalCase.toolFixture().legalQueryTerms(), "toolFixture.legalQueryTerms", evalCase.id());
        requireText(evalCase.toolFixture().legalResult(), "toolFixture.legalResult");
        require(evalCase.toolFixture().riskFacts() != null,
                "toolFixture.riskFacts 不能为空：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().crossBorderRatio() >= 0
                        && evalCase.toolFixture().riskFacts().crossBorderRatio() <= 100,
                "跨境比例必须处于 0-100：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().nightTransactionRatio() >= 0
                        && evalCase.toolFixture().riskFacts().nightTransactionRatio() <= 100,
                "夜间比例必须处于 0-100：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().largeTransactionCount() >= 0,
                "大额交易笔数不能为负：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().transactionPatternSeverity() >= 0
                        && evalCase.toolFixture().riskFacts().transactionPatternSeverity() <= 2,
                "交易模式风险等级必须处于 0-2：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().uboRiskSeverity() >= 0
                        && evalCase.toolFixture().riskFacts().uboRiskSeverity() <= 2,
                "UBO 风险等级必须处于 0-2：" + evalCase.id());
        require(!evalCase.toolFixture().riskFacts().transactionRiskExplained()
                        || evalCase.toolFixture().riskFacts().transactionDataComplete(),
                "交易数据不完整时不能标记风险已解释：" + evalCase.id());
        require(!evalCase.toolFixture().riskFacts().transactionRiskExplained()
                        || evalCase.toolFixture().riskFacts().transactionPatternSeverity() < 2,
                "高风险交易模式不能同时标记为风险已解释：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().maxSanctionSeverity() >= 0,
                "名单等级不能为负：" + evalCase.id());
        require(evalCase.toolFixture().riskFacts().sanctionHit()
                        || evalCase.toolFixture().riskFacts().maxSanctionSeverity() == 0,
                "未命中名单时 maxSanctionSeverity 必须为 0：" + evalCase.id());
    }

    private void validateExpected(AgentEvalCase evalCase) {
        require(evalCase.expected() != null, "案例 expected 不能为空：" + evalCase.id());
        require(ALLOWED_RISK_LEVELS.contains(evalCase.expected().riskLevel()),
                "案例风险等级非法：" + evalCase.id());
        requireList(evalCase.expected().requiredTools(), "expected.requiredTools", evalCase.id());
        require(new HashSet<>(evalCase.expected().requiredTools()).equals(KNOWN_TOOLS),
                "案例必须声明四个标准工具且不能重复：" + evalCase.id());
        requireList(evalCase.expected().requiredRiskSignals(), "expected.requiredRiskSignals", evalCase.id());
        requireCodes(evalCase.expected().requiredFindingCodes(), "expected.requiredFindingCodes",
                evalCase.id(), AgentEvalVocabulary.FINDING_CODES);
        requireCodes(evalCase.expected().allowedFindingCodes(), "expected.allowedFindingCodes",
                evalCase.id(), AgentEvalVocabulary.FINDING_CODES);
        require(new HashSet<>(evalCase.expected().allowedFindingCodes())
                        .containsAll(evalCase.expected().requiredFindingCodes()),
                "requiredFindingCodes 必须是 allowedFindingCodes 的子集：" + evalCase.id());
        requireList(evalCase.expected().requiredActions(), "expected.requiredActions", evalCase.id());
        requireCodes(evalCase.expected().requiredActions(), "expected.requiredActions",
                evalCase.id(), AgentEvalVocabulary.ACTION_CODES);
        requireCodes(evalCase.expected().allowedActions(), "expected.allowedActions",
                evalCase.id(), AgentEvalVocabulary.ACTION_CODES);
        require(new HashSet<>(evalCase.expected().allowedActions())
                        .containsAll(evalCase.expected().requiredActions()),
                "requiredActions 必须是 allowedActions 的子集：" + evalCase.id());
        requireList(evalCase.expected().acceptableLegalTopics(), "expected.acceptableLegalTopics", evalCase.id());
        requireList(evalCase.expected().forbiddenClaimCodes(), "expected.forbiddenClaimCodes", evalCase.id());
        if (evalCase.expected().mustEscalate()) {
            require(evalCase.expected().requiredActions().contains("MANUAL_REVIEW"),
                    "mustEscalate 案例必须要求 MANUAL_REVIEW：" + evalCase.id());
        }
    }

    private void validateAnnotation(AgentEvalCase evalCase) {
        require(evalCase.annotation() != null, "案例 annotation 不能为空：" + evalCase.id());
        requireText(evalCase.annotation().rationale(), "annotation.rationale");
        requireList(evalCase.annotation().factReferences(), "annotation.factReferences", evalCase.id());
        require(ALLOWED_REVIEW_STATUS.contains(evalCase.annotation().reviewStatus()),
                "案例 reviewStatus 非法：" + evalCase.id());
        requireText(evalCase.annotation().reviewerNote(), "annotation.reviewerNote");
    }

    private void requireList(List<String> values, String field, String caseId) {
        require(values != null && !values.isEmpty(), field + " 不能为空：" + caseId);
        require(values.stream().allMatch(v -> v != null && !v.isBlank()), field + " 包含空值：" + caseId);
        require(new HashSet<>(values).size() == values.size(), field + " 包含重复值：" + caseId);
    }

    private void requireCodes(List<String> values, String field, String caseId, Set<String> vocabulary) {
        requireList(values, field, caseId);
        require(vocabulary.containsAll(values), field + " 包含闭集之外的代码：" + caseId);
    }

    private void requireText(String value, String field) {
        require(value != null && !value.isBlank(), field + " 不能为空");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Agent 评测数据集校验失败：" + message);
        }
    }

    private Map<String, Long> counts(List<AgentEvalCase> cases, Function<AgentEvalCase, String> classifier) {
        return cases.stream().collect(Collectors.groupingBy(classifier, TreeMap::new, Collectors.counting()));
    }

    public record AgentEvalDatasetSummary(
            String datasetId,
            String version,
            String sourceType,
            String annotationMethod,
            String reviewStatus,
            int totalCases,
            Map<String, Long> splitCounts,
            Map<String, Long> scenarioCounts,
            Map<String, Long> riskLevelCounts
    ) {
    }
}
