package com.bank.aml.rag.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地 bge-reranker-base（Cross-Encoder）重排模型：实现 LangChain4j {@link ScoringModel}。
 * <p>对 query-doc 对做交叉编码打分，用于召回结果精排。模型不可用时标记 available=false，上层降级。
 */
@Component
public class BgeRerankerScoringModel implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankerScoringModel.class);

    private final RerankModelProvider modelProvider;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private volatile boolean available = false;

    public BgeRerankerScoringModel(RerankModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    @PostConstruct
    void init() {
        try {
            Path dir = modelProvider.locateModel();
            if (dir == null) {
                log.warn("rerank 模型不可用，RAG 将降级为无 rerank");
                return;
            }
            this.env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(dir.resolve("model.onnx").toString(), new OrtSession.SessionOptions());
            this.tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"));
            this.available = true;
            log.info("bge-reranker 模型加载成功，rerank 已启用");
        } catch (Exception e) {
            log.warn("bge-reranker 加载失败，降级为无 rerank：{}", e.getMessage());
            this.available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        List<Double> scores = new ArrayList<>();
        for (TextSegment segment : segments) {
            scores.add(crossScore(query, segment.text()));
        }
        return Response.from(scores);
    }

    private double crossScore(String query, String document) {
        if (!available) {
            return 0.0;
        }
        try {
            Encoding encoding = tokenizer.encode(query, document, true, false);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();

            OnnxTensor inputIds = OnnxTensor.createTensor(env, new long[][]{ids});
            OnnxTensor attentionMask = OnnxTensor.createTensor(env, new long[][]{mask});
            Map<String, OnnxTensor> inputs = Map.of("input_ids", inputIds, "attention_mask", attentionMask);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][] logits = (float[][]) result.get(0).getValue();
                return logits[0][0];
            }
        } catch (Exception e) {
            log.warn("rerank 打分失败，返回 0：{}", e.getMessage());
            return 0.0;
        }
    }
}
