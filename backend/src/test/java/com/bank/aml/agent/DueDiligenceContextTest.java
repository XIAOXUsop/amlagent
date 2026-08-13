package com.bank.aml.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DueDiligenceContextTest {

    @Test
    void promptDistinguishesTrustedAndUntrustedFields() {
        var ctx = new DueDiligenceContext(
                1L, "C001", "张伟", "110101198506123456", "2026-08-13",
                List.of("跨境", "夜间"), "大额频繁跨国转账", "请尽调");
        String prompt = ctx.toPrompt();
        // 可信字段
        assertThat(prompt).contains("客户证件号：110101198506123456");
        assertThat(prompt).contains("法规检索关键词");
        assertThat(prompt).contains("跨境、夜间");
        // 不可信标记
        assertThat(prompt).contains("不可信文本");
        assertThat(prompt).contains("禁止自行生成或修改客户身份信息");
    }

    @Test
    void promptContainsTrustedIdentityForTools() {
        var ctx = new DueDiligenceContext(
                1L, "C002", "王强", "440301197809112233", "2026-08-13",
                List.of("现金"), "拆分交易", "请尽调");
        assertThat(ctx.toPrompt()).contains("客户名称：王强");
        assertThat(ctx.toPrompt()).contains("客户证件号：440301197809112233");
    }
}
