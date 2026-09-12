package com.bank.aml.assistant.context;

import com.bank.aml.assistant.config.AssistantProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文治理器的装配。
 *
 * <p>
 * 整块由 {@code aml.assistant.context.enabled} 控制（默认 true）；置 false 时 不注册治理器
 * Bean，调用方回退到旧的窗口行为——这是一键回滚开关。
 */
@Configuration
@ConditionalOnProperty(prefix = "aml.assistant.context", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AssistantContextConfiguration {

    @Bean
    public ContextTokenEstimator contextTokenEstimator() {
        // 默认确定性估算：零依赖、纯函数，保证"同输入同输出"与全离线可测
        return new DeterministicTokenEstimator();
    }

    @Bean
    public ContextCompressor contextCompressor(AssistantProperties properties) {
        var context = properties.getContext();
        return new ContextCompressor(context.getAnswerHeadChars(), context.getAnswerTailChars());
    }

    @Bean
    public ContextGovernor contextGovernor(ContextTokenEstimator estimator, ContextCompressor compressor) {
        return new ContextGovernor(estimator, compressor);
    }

}
