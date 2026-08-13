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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * rerank 模型定位与下载：优先本地缓存，缺失时从国内镜像（hf-mirror）下载 bge-reranker-base。
 * 下载失败返回 null，上层优雅降级为无 rerank。
 */
@Component
public class RerankModelProvider {

    private static final Logger log = LoggerFactory.getLogger(RerankModelProvider.class);
    private static final String BASE_URL = "https://hf-mirror.com/Xenova/bge-reranker-base/resolve/main";

    private final Path modelDir;

    public RerankModelProvider(@Value("${aml.rag.rerank.model-dir:${user.home}/.cache/aml-reranker/bge-reranker-base}") String modelDir) {
        this.modelDir = Path.of(modelDir);
    }

    /** 返回包含 model.onnx 与 tokenizer.json 的目录；不可用时返回 null */
    public Path locateModel() {
        Path onnx = modelDir.resolve("model.onnx");
        Path tokenizer = modelDir.resolve("tokenizer.json");
        if (Files.exists(onnx) && Files.exists(tokenizer)) {
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

    private void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IOException("下载失败 HTTP " + response.statusCode() + "：" + url);
        }
        log.info("已下载 rerank 模型文件：{}", target.getFileName());
    }
}
