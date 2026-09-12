package com.bank.aml.dto;

import com.bank.aml.datasource.entity.CaseEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDtoTest {

    @Test
    void unknownInternalFailureMessageIsNotExposed() {
        CaseEntity entity = new CaseEntity();
        entity.setFailureCode("UNCLASSIFIED");
        entity.setFailureMessage("database at C:\\secret\\data failed: password=raw");

        CaseDto response = CaseDto.from(entity);

        assertThat(response.failureMessage()).isEqualTo("工作流执行异常，请稍后重试");
        assertThat(response.failureMessage()).doesNotContain("secret", "password", "database");
    }

}
