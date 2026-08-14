package com.bank.aml.rag.rerank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * rerank 模型定位与下载：优先本地缓存，缺失时从国内镜像（hf-mirror）下载 bge-reranker-base。
 * <p>下载安全：写入 {@code .part} 临时文件，校验 HTTP 200 后原子 move 到正式路径，
 * 避免残缺文件被误判为可用模型。下载失败返回 null，上层优雅降级为无 rerank。
 */
@Component
public class RerankModelProvider {

    private static final Logger log = LoggerFactory.getLogger(RerankModelProvider.class);
    private static final String BASE_URL = "https://hf-mirror.com/Xenova/bge-reranker-base/resolve/main";

    private final Path modelDir;

    public RerankModelProvider(@Value("${aml.rag.rerank.model-dir:${user.home}/.cache/aml-reranker/bge-reranker-base}") String modelDir) {
        this.modelDir = Path.of(modelDir);
    }

    /** 返回包含 model.onnx 与 tokenizer.json 的目录；不可用或文件残缺时返回 null */
    public Path locateModel() {
        Path onnx = modelDir.resolve("model.onnx");
        Path tokenizer = modelDir.resolve("tokenizer.json");
        if (isComplete(onnx) && isComplete(tokenizer)) {
            return modelDir;
        }
        try {
            Files.createDirectories(modelDir);
            download(BASE_URL + "/onnx/model_quantized.onnx", onnx);
            download(BASE_URL + "/tokenizer.json", tokenizer);
            return modelDir;
        } catch (Exception e) {
            log.warn("rerank 模型下载失败，降级为无 rerank：{}", e.getMessage());
            return null;
        }
    }

    /** 文件完整且非空才算可用，避免残缺文件被当作模型加载 */
    private boolean isComplete(Path file) {
        try {
            return Files.exists(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void download(String url, Path target) throws IOException, InterruptedException {
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
}
