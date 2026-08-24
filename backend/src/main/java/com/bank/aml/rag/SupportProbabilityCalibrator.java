package com.bank.aml.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 将 Reranker 原始分数校准为支持概率 supportProbability。
 * <ul>
 *   <li>{@code platt}：Platt Scaling（逻辑回归）拟合，将 logit 分数映射到概率区间；</li>
 *   <li>{@code isotonic}：保序回归（等渗回归），无需单调函数假设，直接学习分数→概率保序映射。</li>
 * </ul>
 * 未拟合（默认）时使用恒等 logit 变换 {@code sigmoid(raw)}。拟合数据为 (score, relevant) 的标注样本。
 */
@Component
public class SupportProbabilityCalibrator {

    private static final Logger log = LoggerFactory.getLogger(SupportProbabilityCalibrator.class);

    /** 拟合方法：platt | isotonic */
    private final String method;

    // Platt Scaling 参数 p = sigmoid(a * score + b)
    private double plattA = 1.0;
    private double plattB = 0.0;

    // Isotonic：升序断点 (score, calibrated)
    private List<double[]> isotonicBreaks = List.of();

    private boolean fitted = false;

    public SupportProbabilityCalibrator(@Value("${aml.rag.support.calibration-method:platt}") String method) {
        this.method = method == null || method.isBlank() ? "platt" : method;
    }

    /** 便捷构造（测试/无 Spring 场景）。 */
    public SupportProbabilityCalibrator() {
        this("platt");
    }

    public boolean isFitted() {
        return fitted;
    }

    /** 校准原始分数 -> [0,1] 支持概率。 */
    public double calibrate(double rawScore) {
        if ("isotonic".equalsIgnoreCase(method) && fitted) {
            return isotonic(rawScore);
        }
        return sigmoid(plattA * rawScore + plattB);
    }

    /**
     * 从人工标注样本拟合校准参数。样本形如 {@code (rawScore, relevant)}，
     * {@code relevant} 表示该 query-doc 对是否被标注为相关。
     */
    public void fit(List<double[]> samples) {
        if (samples == null || samples.size() < 2) {
            log.warn("校准样本不足（{}），保留默认映射", samples == null ? 0 : samples.size());
            return;
        }
        if ("isotonic".equalsIgnoreCase(method)) {
            isotonicBreaks = fitIsotonic(samples);
            fitted = true;
        } else {
            double[] ab = fitPlatt(samples);
            plattA = ab[0];
            plattB = ab[1];
            fitted = true;
        }
        log.info("支持分数校准完成：method={} samples={} a={} b={} breaks={}",
                method, samples.size(), Math.round(plattA * 100) / 100.0,
                Math.round(plattB * 100) / 100.0, isotonicBreaks.size());
    }

    /** Platt Scaling：对单个变量的二分类做梯度下降拟合 a、b。 */
    private double[] fitPlatt(List<double[]> samples) {
        double a = 1.0;
        double b = 0.0;
        double learningRate = 0.2;
        for (int iter = 0; iter < 500; iter++) {
            double gA = 0;
            double gB = 0;
            for (double[] sample : samples) {
                double x = sample[0];
                double label = sample[1];
                double p = sigmoid(a * x + b);
                gA += (p - label) * x;
                gB += (p - label);
            }
            a -= learningRate * gA / samples.size();
            b -= learningRate * gB / samples.size();
        }
        return new double[]{a, b};
    }

    /** Isotonic（PAVA）：保序回归，断点按 score 升序返回 (score, calibrated)。 */
    private List<double[]> fitIsotonic(List<double[]> samples) {
        List<double[]> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingDouble(s -> s[0]));
        // PAVA 块合并
        List<List<double[]>> blocks = new ArrayList<>();
        for (double[] sample : sorted) {
            List<double[]> block = new ArrayList<>();
            block.add(sample);
            blocks.add(block);
            while (blocks.size() >= 2 && mean(blocks.get(blocks.size() - 2)) > mean(blocks.get(blocks.size() - 1))) {
                List<double[]> merged = new ArrayList<>();
                merged.addAll(blocks.get(blocks.size() - 2));
                merged.addAll(blocks.get(blocks.size() - 1));
                blocks.remove(blocks.size() - 1);
                blocks.remove(blocks.size() - 1);
                blocks.add(merged);
            }
        }
        List<double[]> breaks = new ArrayList<>();
        for (List<double[]> block : blocks) {
            double mean = mean(block);
            breaks.add(new double[]{block.get(block.size() - 1)[0], mean});
        }
        return breaks;
    }

    private double isotonic(double score) {
        if (isotonicBreaks.isEmpty()) return sigmoid(score);
        if (score <= isotonicBreaks.getFirst()[0]) return isotonicBreaks.getFirst()[1];
        double[] last = isotonicBreaks.get(isotonicBreaks.size() - 1);
        if (score >= last[0]) return last[1];
        for (int i = 0; i < isotonicBreaks.size() - 1; i++) {
            double[] lower = isotonicBreaks.get(i);
            double[] upper = isotonicBreaks.get(i + 1);
            if (score >= lower[0] && score <= upper[0]) {
                double t = (score - lower[0]) / Math.max(1e-9, upper[0] - lower[0]);
                return lower[1] + t * (upper[1] - lower[1]);
            }
        }
        return sigmoid(score);
    }

    private double mean(List<double[]> block) {
        double sum = 0;
        for (double[] sample : block) sum += sample[1];
        return sum / block.size();
    }

    private double sigmoid(double value) {
        // 数值稳定：避免 exp 溢出
        return 1.0 / (1.0 + Math.exp(-Math.min(40.0, Math.max(-40.0, value))));
    }
}