package com.bank.aml.rag.ingestion;

import java.util.List;

/** 候选法规索引发布前的质量评测端口。 */
public interface RagPublicationEvaluator {

    GateResult evaluate(String candidateVersion, int segmentCount);

    record GateResult(boolean passed, String qualityJson, List<String> failures) {

        public static final GateResult PASSED_WITHOUT_REPORT = new GateResult(true,
                "{\"smokeSearch\":true,\"gates\":[]}", List.of());

        public GateResult {
            failures = List.copyOf(failures);
        }

    }

}
