package com.bank.aml.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 合规尽调 Agent 结构化输出的安全与成本边界。 */
@ConfigurationProperties(prefix = "aml.agent-output")
@Validated
public class AgentOutputProperties {

    @Min(1)
    @Max(1_000_000)
    private int maxTextCharacters = 8_000;

    @Min(1)
    @Max(10_000)
    private int maxListItems = 50;

    public int getMaxTextCharacters() {
        return maxTextCharacters;
    }

    public void setMaxTextCharacters(int maxTextCharacters) {
        this.maxTextCharacters = maxTextCharacters;
    }

    public int getMaxListItems() {
        return maxListItems;
    }

    public void setMaxListItems(int maxListItems) {
        this.maxListItems = maxListItems;
    }

}
