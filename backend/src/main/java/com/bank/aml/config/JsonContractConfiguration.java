package com.bank.aml.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对外 JSON 精确数值契约：BigDecimal 一律序列化为十进制字符串，避免客户端二进制浮点损失。 */
@Configuration
public class JsonContractConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer preciseDecimalJsonCustomizer() {
        return builder -> builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);
    }

}
