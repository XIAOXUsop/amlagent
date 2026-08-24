package com.bank.aml.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupportProbabilityCalibratorTest {

    @Test
    void defaultMappingIsSigmoidIdentity() {
        SupportProbabilityCalibrator calibrator = new SupportProbabilityCalibrator("platt");
        assertThat(calibrator.calibrate(0.0)).isEqualTo(0.5);
        assertThat(calibrator.calibrate(3.0)).isGreaterThan(0.9);
        assertThat(calibrator.calibrate(-3.0)).isLessThan(0.1);
    }

    @Test
    void plattScalingFitsFromHumanLabels() {
        SupportProbabilityCalibrator calibrator = new SupportProbabilityCalibrator("platt");
        // 高分相关、低分不相关的标注样本
        calibrator.fit(List.of(
                new double[]{-3.0, 0.0}, new double[]{-2.0, 0.0}, new double[]{-0.5, 0.0},
                new double[]{0.0, 1.0}, new double[]{1.0, 1.0}, new double[]{2.5, 1.0},
                new double[]{3.5, 1.0}));

        assertThat(calibrator.isFitted()).isTrue();
        // 校准后仍保持单调，且对得分的区分更强
        double low = calibrator.calibrate(-2.0);
        double high = calibrator.calibrate(3.0);
        assertThat(high).isGreaterThan(low);
        assertThat(low).isLessThan(0.4);
        assertThat(high).isGreaterThan(0.7);
    }

    @Test
    void isotonicRegressionProducesMonotoneProbabilities() {
        SupportProbabilityCalibrator calibrator = new SupportProbabilityCalibrator("isotonic");
        calibrator.fit(List.of(
                new double[]{-4.0, 0.0}, new double[]{-3.0, 0.0}, new double[]{-1.0, 0.0},
                new double[]{1.0, 1.0}, new double[]{2.0, 1.0}, new double[]{4.0, 1.0}));

        assertThat(calibrator.isFitted()).isTrue();
        assertThat(calibrator.calibrate(0.0)).isBetween(0.0, 1.0);
        assertThat(calibrator.calibrate(1.0)).isGreaterThanOrEqualTo(calibrator.calibrate(-1.0));
    }
}