package com.bank.aml.evaluation;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Case-scoped tools backed exclusively by one evaluation fixture.
 *
 * <p>Create a fresh instance for every evaluation case. Identity-bearing calls must match the
 * current case exactly; invalid calls return a generic error and never expose fixture content.</p>
 */
public final class AgentEvalFixtureTools {

    public static final String ARGUMENT_VALIDATION_FAILED = "ARGUMENT_VALIDATION_FAILED";
    public static final String LEGAL_QUERY_VALIDATION_FAILED =
            "ARGUMENT_VALIDATION_FAILED: query must copy at least one provided legal search keyword verbatim";
    public static final String TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";

    private final AgentEvalDataset.AgentEvalCase evalCase;
    private final List<AgentEvalToolCallTrace> callTraces = new CopyOnWriteArrayList<>();

    public AgentEvalFixtureTools(AgentEvalDataset.AgentEvalCase evalCase) {
        this.evalCase = Objects.requireNonNull(evalCase, "evalCase must not be null");
        Objects.requireNonNull(evalCase.input(), "evalCase.input must not be null");
        Objects.requireNonNull(evalCase.toolFixture(), "evalCase.toolFixture must not be null");
    }

    @Tool(
            name = "transactionProfile",
            value = "Query the current evaluation customer's transaction risk profile."
    )
    public String transactionProfile(
            @P(name = "customerId", value = "Exact customer identifier from the case") String customerId
    ) {
        Map<String, String> arguments = arguments("customerId", customerId);
        return invoke(
                "transactionProfile",
                arguments,
                () -> exact(customerId, evalCase.input().customerId()),
                () -> evalCase.toolFixture().transactionResult()
        );
    }

    @Tool(
            name = "corporateProfile",
            value = "Query the current evaluation customer's corporate ownership and UBO profile."
    )
    public String corporateProfile(
            @P(name = "customerId", value = "Exact customer identifier from the case") String customerId
    ) {
        Map<String, String> arguments = arguments("customerId", customerId);
        return invoke(
                "corporateProfile",
                arguments,
                () -> exact(customerId, evalCase.input().customerId()),
                () -> evalCase.toolFixture().corporateResult()
        );
    }

    @Tool(
            name = "checkSanctions",
            value = "Check sanctions using the current evaluation customer's exact name and identity number."
    )
    public String checkSanctions(
            @P(name = "customerName", value = "Exact customer name from the case") String customerName,
            @P(name = "identityNumber", value = "Exact identity number from the case") String identityNumber
    ) {
        Map<String, String> arguments = arguments(
                "customerName", customerName,
                "identityNumber", identityNumber
        );
        return invoke(
                "checkSanctions",
                arguments,
                () -> exact(customerName, evalCase.input().customerName())
                        && exact(identityNumber, evalCase.input().identityNumber()),
                () -> evalCase.toolFixture().sanctionResult()
        );
    }

    @Tool(
            name = "searchLegal",
            value = "Search frozen legal evidence. The query must copy at least one legalSearchKeyword from the case input verbatim."
    )
    public String searchLegal(
            @P(name = "query", value = "Query containing a verbatim legalSearchKeyword from the case input") String query
    ) {
        Map<String, String> arguments = arguments("query", query);
        String result = invoke(
                "searchLegal",
                arguments,
                () -> legalQueryValid(query),
                () -> evalCase.toolFixture().legalResult()
        );
        return ARGUMENT_VALIDATION_FAILED.equals(result) ? LEGAL_QUERY_VALIDATION_FAILED : result;
    }

    /** Returns a stable snapshot even while LangChain4j executes tools concurrently. */
    public List<AgentEvalToolCallTrace> traces() {
        return List.copyOf(callTraces);
    }

    private String invoke(
            String toolName,
            Map<String, String> arguments,
            BooleanSupplier argumentValidator,
            Supplier<String> resultSupplier
    ) {
        long startedAt = System.nanoTime();
        if (!argumentValidator.getAsBoolean()) {
            callTraces.add(new AgentEvalToolCallTrace(
                    toolName,
                    arguments,
                    false,
                    false,
                    elapsedMs(startedAt),
                    null,
                    ARGUMENT_VALIDATION_FAILED
            ));
            return ARGUMENT_VALIDATION_FAILED;
        }

        try {
            String result = Objects.requireNonNull(resultSupplier.get(), "fixture result must not be null");
            callTraces.add(new AgentEvalToolCallTrace(
                    toolName,
                    arguments,
                    true,
                    true,
                    elapsedMs(startedAt),
                    sha256(result),
                    null
            ));
            return result;
        } catch (RuntimeException exception) {
            callTraces.add(new AgentEvalToolCallTrace(
                    toolName,
                    arguments,
                    false,
                    true,
                    elapsedMs(startedAt),
                    null,
                    TOOL_EXECUTION_FAILED
            ));
            return TOOL_EXECUTION_FAILED;
        }
    }

    private static boolean exact(String actual, String expected) {
        return actual != null && actual.equals(expected);
    }

    private boolean legalQueryValid(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = normalizeQuery(query);
        return evalCase.toolFixture().legalQueryTerms().stream()
                .map(AgentEvalFixtureTools::normalizeQuery)
                .anyMatch(normalized::contains);
    }

    private static String normalizeQuery(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static long elapsedMs(long startedAt) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedAt);
        return elapsedNanos == 0L ? 0L : Math.max(1L, elapsedNanos / 1_000_000L);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static Map<String, String> arguments(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be supplied as name/value pairs");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            result.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return result;
    }
}
