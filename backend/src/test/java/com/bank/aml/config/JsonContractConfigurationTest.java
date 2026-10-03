package com.bank.aml.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对外 JSON 的数值契约：{@link BigDecimal} 一律序列化为**十进制字符串**。
 *
 * <p>
 * 这条契约此前**没有任何测试覆盖**——`JsonContractConfiguration` 定义了整个对外接口的
 * 数值表示，却没人盯着它。而它的失效方式是**静默**的：客户端拿到二进制浮点数会出现 精度损失，接口仍然返回 200、字段也还在，只是值悄悄变了。
 *
 * <p>
 * 用 {@link JsonTest} 而不是纯单测：纯单测只能验证"这个 customizer 被调用时做了什么"，
 * 验证不了**它有没有被接线进去**。而真正会坏掉的恰恰是接线——换一个 Spring Boot 大版本时， customizer 的接口本身可能改名/换包（例如 Boot 4
 * 用 Jackson 3， `Jackson2ObjectMapperBuilderCustomizer` 就不存在了），那时这个 bean 会被**整个忽略**，
 * 编译照过、别的测试照绿，只有这条契约没了。{@code @JsonTest} 起的是 Boot 真实的 Jackson 自动配置，能盖到那一层。
 */
@JsonTest
@Import(JsonContractConfiguration.class)
class JsonContractConfigurationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesBigDecimalAsDecimalString() throws Exception {
        assertThat(objectMapper.writeValueAsString(new BigDecimal("320000.00"))).isEqualTo("\"320000.00\"");
    }

    @Test
    void keepsScaleInsteadOfNormalizing() throws Exception {
        // 尾随 0 是金额口径的一部分，不能被"归一"掉
        assertThat(objectMapper.writeValueAsString(new BigDecimal("0.50"))).isEqualTo("\"0.50\"");
    }

    @Test
    void serializesBigDecimalInsideObjectsToo() throws Exception {
        assertThat(objectMapper.writeValueAsString(new Amount(new BigDecimal("1234.56"))))
            .isEqualTo("{\"amount\":\"1234.56\"}");
    }

    /** 用真实对象包一层：契约要在嵌套结构里也成立，而不只在顶层标量上。 */
    record Amount(BigDecimal amount) {
    }

}
