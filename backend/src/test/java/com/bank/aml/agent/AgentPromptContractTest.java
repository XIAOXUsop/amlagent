package com.bank.aml.agent;

import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptContractTest {

    @Test
    void promptContainsEveryProductionVocabularyCode() throws Exception {
        Method investigate = DueDiligenceAgent.class.getDeclaredMethod("investigate", String.class);
        SystemMessage annotation = investigate.getAnnotation(SystemMessage.class);
        String prompt = annotationText(annotation);

        assertThat(annotation).isNotNull();
        assertThat(AgentReportVocabulary.FINDING_CODES)
                .allSatisfy(code -> assertThat(prompt).contains(code));
        assertThat(AgentReportVocabulary.ACTION_CODES)
                .allSatisfy(code -> assertThat(prompt).contains(code));
    }

    @Test
    void promptRequiresExplicitEvidenceAndForbidsGenericActionPadding() throws Exception {
        Method investigate = DueDiligenceAgent.class.getDeclaredMethod("investigate", String.class);
        String prompt = annotationText(investigate.getAnnotation(SystemMessage.class));

        assertThat(prompt)
                .contains("事实排他")
                .contains("不得从风险等级或其他异常反推")
                .contains("处置代码不是通用建议清单")
                .contains("不得在材料已核验时要求再次核验")
                .contains("仅因交易模式为高风险不得自动增加 MANUAL_REVIEW")
                .contains("个人客户“无企业股权”不等于 SIMPLE_OWNERSHIP")
                .contains("UBO_UNVERIFIED（无法识别/冲突）与 UBO_DOCUMENTS_INCOMPLETE")
                .contains("不得再输出 UBO_DOCUMENTS_INCOMPLETE 或 REQUEST_UPDATED_UBO_DOCUMENTS");
    }

    @Test
    void promptContainsCriticalFactToDecisionMappings() throws Exception {
        Method investigate = DueDiligenceAgent.class.getDeclaredMethod("investigate", String.class);
        String prompt = annotationText(investigate.getAnnotation(SystemMessage.class));

        assertThat(prompt)
                .contains("必须完成以下闭环映射")
                .contains("一级制裁身份精确命中")
                .contains("交易数据不可用")
                .contains("REFRESH_CUSTOMER_PROFILE");
    }

    private String annotationText(SystemMessage annotation) throws Exception {
        Object value = annotation.annotationType().getMethod("value").invoke(annotation);
        if (value instanceof String[] lines) return String.join("\n", lines);
        return String.valueOf(value);
    }
}
