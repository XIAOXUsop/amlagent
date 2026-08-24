package com.bank.aml.rag.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 知识入库前安全扫描；只输出原因代码，不记录命中的敏感原文。 */
@Component
public class LegalCorpusSecurityScanner {
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final List<String> INJECTION_MARKERS = List.of(
            "ignore previous", "ignore all previous", "system prompt", "developer message",
            "忽略之前", "忽略以上", "泄露系统提示", "执行以下指令", "调用任意工具");
    private static final Pattern SECRET_LIKE = Pattern.compile(
            "(?i)(-----BEGIN PRIVATE KEY-----|(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{12,}|api_?key\\s*[:=]\\s*[^\\s]{8,})");
    private final long maxBytes;

    public LegalCorpusSecurityScanner(@Value("${aml.rag.ingestion.max-document-bytes:5242880}") long maxBytes) {
        this.maxBytes = Math.max(1024, maxBytes);
    }

    public ScanResult scan(Path path) {
        List<String> reasons = new ArrayList<>();
        try {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                return new ScanResult(path.getFileName().toString(), "0".repeat(64), List.of("UNSAFE_FILE_TYPE"));
            }
            long size = Files.size(path);
            if (size <= 0) reasons.add("EMPTY_DOCUMENT");
            if (size > maxBytes) {
                // 超限文件只流式计算摘要，不把整个内容读进堆内存。
                return new ScanResult(path.getFileName().toString(), sha256(path), List.of("DOCUMENT_TOO_LARGE"));
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String lower = content.toLowerCase(Locale.ROOT);
            if (content.indexOf('\0') >= 0 || content.chars().anyMatch(ch -> ch < 9 || ch == 11 || ch == 12)) {
                reasons.add("CONTROL_CHARACTER_DETECTED");
            }
            if (INJECTION_MARKERS.stream().anyMatch(lower::contains)) reasons.add("PROMPT_INJECTION_CONTENT");
            if (SECRET_LIKE.matcher(content).find()) reasons.add("SECRET_LIKE_CONTENT");
            if (ID_CARD.matcher(content).find()) reasons.add("IDENTITY_DATA_CONTENT");
            return new ScanResult(path.getFileName().toString(), sha256(path), List.copyOf(reasons));
        } catch (Exception e) {
            return new ScanResult(path.getFileName().toString(), "0".repeat(64), List.of("DOCUMENT_READ_FAILED"));
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record ScanResult(String sourceFile, String fileHash, List<String> reasonCodes) {
        public boolean safe() { return reasonCodes.isEmpty(); }
    }
}
