package com.bank.aml.rag.ingestion;

import com.bank.aml.config.RagProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法规文档导入器：应用启动时将 data/legal 下的法规/案例文档切分、附加证据元数据并向量化写入 PGVector。
 * <p>每条片段生成唯一 {@code evidenceId}（LEGAL-&lt;文件序号&gt;-&lt;段落序号&gt;），供报告 Evidence 引用与回溯。
 */
@Component
public class LegalDocIngestor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegalDocIngestor.class);
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[一二三四五六七八九十百千0-9]+条");
    private static final Pattern DOC_NUMBER_PATTERN = Pattern.compile("[（(]?[^（(]*令[〔【]?\\d{4}[〕】]?第\\d+号[）)]?");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagProperties props;

    public LegalDocIngestor(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                            RagProperties props) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Path> files = loadDocFiles();
            if (files.isEmpty()) {
                log.warn("RAG 导入：目录 {} 下未找到法规文档，跳过导入", props.getDataDir());
                return;
            }
            // 内容哈希幂等：法规未变化时跳过重建，避免每次启动 removeAll 清空索引（旧快照读到空/新索引）
            String contentHash = computeContentHash(files);
            if (contentHash.equals(readIndexVersion())) {
                log.info("RAG 索引未变化（hash={}），跳过重建", contentHash.substring(0, 8));
                return;
            }
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                segments.addAll(splitFile(files.get(i), i));
            }
            if (segments.isEmpty()) {
                log.warn("RAG 导入：无有效文档片段");
                return;
            }
            embeddingStore.removeAll();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            writeIndexVersion(contentHash);
            log.info("RAG 导入完成：{} 个文档片段向量化入库（表 {}，hash={}）",
                    segments.size(), props.getPg().getTable(), contentHash.substring(0, 8));
        } catch (Exception e) {
            log.error("RAG 文档导入失败", e);
        }
    }

    /** 法规索引内容哈希（所有文档内容拼接后的 SHA-256），用于幂等导入与版本审计 */
    private String computeContentHash(List<Path> files) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (Path file : files) {
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path versionFile() {
        return Path.of(props.getDataDir(), ".index-version");
    }

    private String readIndexVersion() {
        try {
            Path f = versionFile();
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8).strip() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void writeIndexVersion(String hash) {
        try {
            Files.writeString(versionFile(), hash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入法规索引版本文件失败：{}", e.getMessage());
        }
    }

    private List<Path> loadDocFiles() throws IOException {
        Path dir = Path.of(props.getDataDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted().toList();
        }
    }

    private List<TextSegment> splitFile(Path file, int fileSeq) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String title = file.getFileName().toString().replace(".md", "").replace(".txt", "");
        String documentNumber = extractDocumentNumber(content, title);
        String[] paras = content.split("\\n\\s*\\n");
        List<TextSegment> segments = new ArrayList<>();
        int paraSeq = 1;
        for (String p : paras) {
            String text = p.strip();
            if (text.length() < 20) {
                continue;
            }
            String article = extractArticle(text);
            String evidenceId = "LEGAL-" + (fileSeq + 1) + "-" + paraSeq;
            Metadata metadata = new Metadata()
                    .put("title", title)
                    .put("documentNumber", documentNumber)
                    .put("articleNumber", article)
                    .put("evidenceId", evidenceId);
            segments.add(TextSegment.from(text, metadata));
            paraSeq++;
        }
        return segments;
    }

    private String extractArticle(String text) {
        Matcher m = ARTICLE_PATTERN.matcher(text);
        return m.find() ? m.group() : "";
    }

    private String extractDocumentNumber(String content, String title) {
        Matcher m = DOC_NUMBER_PATTERN.matcher(content);
        return m.find() ? m.group() : title;
    }
}
