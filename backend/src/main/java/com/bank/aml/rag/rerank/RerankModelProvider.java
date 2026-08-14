package com.bank.aml.rag.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * rerank 模型定位与下载：优先本地缓存，缺失时从国内镜像（hf-mirror）下载 bge-reranker-base。
 * <p>下载安全：写入 {@code .part} 临时文件，校验 HTTP 200 与（可选）SHA-256 后原子 move 到正式路径，
 * 避免残缺/损坏文件被误判为可用模型。下载失败返回 null，上层优雅降级为无 rerank。
 */
@Component
public class RerankModelProvider {

    private static final Logger log = LoggerFactory.getLogger(RerankModelProvider.class);
    private static final String BASE_URL = "https://hf-mirror.com/Xenova/bge-reranker-base/resolve/main";

    private final Path modelDir;
    private final String modelSha256;
    private final String tokenizerSha256;

    public RerankModelProvider(
            @Value("${aml.rag.rerank.model-dir:${user.home}/.cache/aml-reranker/bge-reranker-base}") String modelDir,
            @Value("${aml.rag.rerank.model-sha256:}") String modelSha256,
            @Value("${aml.rag.rerank.tokenizer-sha256:}") String tokenizerSha256) {
        this.modelDir = Path.of(modelDir);
        this.modelSha256 = modelSha256;
        this.tokenizerSha256 = tokenizerSha256;
    }

    /** 返回包含 model.onnx 与 tokenizer.json 的目录；不可用或文件损坏时返回 null */
    public Path locateModel() {
        Path onnx = modelDir.resolve("model.onnx");
        Path tokenizer = modelDir.resolve("tokenizer.json");
        if (isComplete(onnx, modelSha256) && isComplete(tokenizer, tokenizerSha256)) {
            return modelDir;
        }
        try {
            Files.createDirectories(modelDir);
            download(BASE_URL + "/onnx/model_quantized.onnx", onnx, modelSha256);
            download(BASE_URL + "/tokenizer.json", tokenizer, tokenizerSha256);
            return modelDir;
        } catch (Exception e) {
            log.warn("rerank 模型下载失败，降级为无 rerank：{}", e.getMessage());
            return null;
        }
    }

    /** 文件非空且（配置了哈希时）SHA-256 匹配，才算可用 */
    private boolean isComplete(Path file, String expectedSha256) {
        try {
            if (!Files.exists(file) || Files.size(file) <= 0) {
                return false;
            }
            if (expectedSha256 == null || expectedSha256.isBlank()) {
                return true; // 未配置哈希，仅校验大小
            }
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                log.warn("rerank 文件哈希不匹配，视为损坏：{}", file.getFileName());
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void download(String url, Path target, String expectedSha256) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(part);
                throw new IOException("下载失败 HTTP " + response.statusCode() + "：" + url);
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actual = sha256(part);
                if (!actual.equalsIgnoreCase(expectedSha256)) {
                    Files.deleteIfExists(part);
                    throw new IOException("下载文件哈希校验失败：" + target.getFileName());
                }
            }
            moveAtomically(part, target);
            log.info("已下载 rerank 模型文件：{}", target.getFileName());
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    private void moveAtomically(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
