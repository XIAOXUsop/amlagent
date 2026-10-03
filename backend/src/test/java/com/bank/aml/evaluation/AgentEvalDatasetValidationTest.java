package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvalDatasetValidationTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void rejectsCorruptedDatasetBeforeEvaluation(String name, Consumer<ObjectNode> mutate, String expectedMessage)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentEvalDatasetLoader loader = new AgentEvalDatasetLoader(mapper);
        ObjectNode dataset = mapper.valueToTree(loader.load());
        ObjectNode firstCase = (ObjectNode) dataset.path("cases").get(0);
        mutate.accept(firstCase);
        AgentEvalDataset corrupted = mapper.treeToValue(dataset, AgentEvalDataset.class);

        assertThatThrownBy(() -> loader.validate(corrupted)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(expectedMessage);
    }

    static Stream<Arguments> invalidCases() {
        return Stream.of(
                invalid("impossible calendar date", node -> input(node).put("asOfDate", "2026-02-30"), "ISO 日期"),
                invalid("non ISO date", node -> input(node).put("asOfDate", "2026/08/01"), "ISO 日期"),
                invalid("embedded hidden split", node -> node.put("split", "TEST"), "split 非法"),
                invalid("missing annotation", node -> node.putNull("annotation"), "annotation"),
                invalid("unknown finding", node -> expected(node).withArray("allowedFindingCodes").add("INVENTED"),
                        "闭集之外"),
                invalid("unknown action", node -> expected(node).withArray("allowedActions").add("INVENTED"), "闭集之外"),
                invalid("duplicate tool", node -> expected(node).withArray("requiredTools").add("searchLegal"), "重复"),
                invalid("missing mandatory tool", node -> expected(node).withArray("requiredTools").remove(0), "四个"),
                invalid("manual flag without manual action", node -> expected(node).put("mustEscalate", true),
                        "MANUAL_REVIEW"),
                invalid("negative cross border ratio", node -> facts(node).put("crossBorderRatio", -1), "0-100"),
                invalid("excessive night ratio", node -> facts(node).put("nightTransactionRatio", 101), "0-100"),
                invalid("non finite ratio", node -> facts(node).put("crossBorderRatio", Double.NaN), "0-100"),
                invalid("explained missing data", node -> facts(node).put("transactionDataComplete", false), "数据不完整"),
                invalid("explained severe pattern", node -> facts(node).put("transactionPatternSeverity", 2), "高风险交易"),
                invalid("sanction hit without a severity", node -> facts(node).put("sanctionHit", true), "必须大于 0"),
                invalid("severity without a sanction hit", node -> facts(node).put("maxSanctionSeverity", 1), "必须为 0"),
                invalid("negative transaction count", node -> facts(node).put("largeTransactionCount", -1), "不能为负"),
                invalid("duplicate annotation reference",
                        node -> ((ObjectNode) node.path("annotation")).withArray("factReferences")
                            .add(node.path("annotation").path("factReferences").get(0).asText()),
                        "重复"));
    }

    private static Arguments invalid(String name, Consumer<ObjectNode> mutate, String expectedMessage) {
        return Arguments.of(name, mutate, expectedMessage);
    }

    private static ObjectNode input(ObjectNode node) {
        return (ObjectNode) node.path("input");
    }

    private static ObjectNode expected(ObjectNode node) {
        return (ObjectNode) node.path("expected");
    }

    private static ObjectNode facts(ObjectNode node) {
        return (ObjectNode) node.path("toolFixture").path("riskFacts");
    }

}
