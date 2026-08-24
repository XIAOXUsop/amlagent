package com.bank.aml.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 从人工标注样本文件拟合支持概率校准器。
 * <p>标注文件为 JSON 数组「[rerankScore, relevant]」，例如：{@code [[-2.3,0],[0.8,1],[2.9,1],...]}，
 * 至少 100~300 条以获得稳定 Platt/Isotonic 参数。未配置路径（默认）时校准器使用恒等 sigmoid 映射。</p>
 */
@Component
public class SupportCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(SupportCalibrationService.class);

    private final SupportProbabilityCalibrator calibrator;
    private final ObjectMapper objectMapper;
    private final String dataPath;

    public SupportCalibrationService(SupportProbabilityCalibrator calibrator, ObjectMapper objectMapper,
                                     @Value("${aml.rag.support.calibration-data-path:}") String dataPath) {
        this.calibrator = calibrator;
        this.objectMapper = objectMapper;
        this.dataPath = dataPath;
    }

    @PostConstruct
    public void init() {
        if (dataPath == null || dataPath.isBlank()) {
            return;
        }
        Path path = Path.of(dataPath);
        if (!Files.isRegularFile(path)) {
            log.warn("支持度校准数据集不存在（{}），使用默认映射", dataPath);
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            List<List<Double>> samples = objectMapper.readValue(input, new TypeReference<List<List<Double>>>() {});
            if (samples == null || samples.size() < 2) {
                log.warn("支持度校准样本不足（{} 条），使用默认映射", samples == null ? 0 : samples.size());
                return;
            }
            calibrator.fit(samples.stream().map(s -> new double[]{s.get(0), s.get(1)}).toList());
            log.info("已从 {} 加载 {} 条标注样本校准支持概率（{}）", dataPath, samples.size(),
                    calibrator.isFitted() ? "已拟合" : "未拟合");
        } catch (Exception e) {
            log.warn("支持度校准数据集加载失败：{}", e.getMessage());
        }
    }
}