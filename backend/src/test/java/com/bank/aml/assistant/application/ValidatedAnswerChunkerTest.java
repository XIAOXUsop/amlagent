package com.bank.aml.assistant.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatedAnswerChunkerTest {
    @Test
    void reconstructsAnswerExactlyAcrossMultipleChunks() {
        String answer = "## 风险结论\n近180天交易需要关注。[证据: TXN-001]";

        List<String> chunks = ValidatedAnswerChunker.split(answer, 8);

        assertTrue(chunks.size() > 1);
        assertEquals(answer, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.codePointCount(0, chunk.length()) <= 8));
    }

    @Test
    void neverSplitsUnicodeSurrogatePair() {
        List<String> chunks = ValidatedAnswerChunker.split("风险🔎证据", 3);

        assertEquals(List.of("风险🔎", "证据"), chunks);
        assertEquals("风险🔎证据", String.join("", chunks));
    }

    @Test
    void handlesEmptyAndRejectsInvalidChunkSize() {
        assertEquals(List.of(), ValidatedAnswerChunker.split("", 8));
        assertEquals(List.of(), ValidatedAnswerChunker.split(null, 8));
        assertThrows(IllegalArgumentException.class, () -> ValidatedAnswerChunker.split("answer", 0));
    }
}
