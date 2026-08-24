package com.bank.aml.assistant.application;

import java.util.ArrayList;
import java.util.List;

/** 将已通过最终护栏的答案按 Unicode 码点切分，避免拆断 emoji 等代理字符。 */
final class ValidatedAnswerChunker {
    private ValidatedAnswerChunker() {
    }

    static List<String> split(String answer, int maxCodePoints) {
        if (answer == null || answer.isEmpty()) return List.of();
        if (maxCodePoints < 1) throw new IllegalArgumentException("maxCodePoints must be positive");

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < answer.length()) {
            int remaining = answer.codePointCount(start, answer.length());
            int end = answer.offsetByCodePoints(start, Math.min(maxCodePoints, remaining));
            chunks.add(answer.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }
}
