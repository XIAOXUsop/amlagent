package com.bank.aml.common;

import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** W1/V2-01~03：调查版本冲突的 HTTP 协议映射 —— 409 + 稳定错误码 + 有界冲突对象。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void revisionConflictMapsTo409WithBoundedConflictObject() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        InvestigationRevisionConflictException ex = new InvestigationRevisionConflictException(
                InvestigationRevisionConflictException.TYPE_HYPOTHESIS, 31L, 4,
                "判断依据的假设已改判（当前版本 4，请求基于版本 3），请刷新后基于最新依据重新确认");

        ResponseEntity<ApiError> response = handler.handleInvestigationRevisionConflict(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVESTIGATION_REVISION_CONFLICT");
        assertThat(response.getBody().conflict()).isNotNull();
        assertThat(response.getBody().conflict().type()).isEqualTo("HYPOTHESIS");
        assertThat(response.getBody().conflict().id()).isEqualTo(31L);
        assertThat(response.getBody().conflict().currentVersion()).isEqualTo(4);
    }

    /** 业务前置条件失败保持原有 412 语义，不升格为版本冲突。 */
    @Test
    void businessPreconditionFailureKeeps412Semantics() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler.handleIllegalState(
                new IllegalStateException("案件已录入调查证据，不能再拆分预警"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PRECONDITION_FAILED");
        assertThat(response.getBody().conflict()).isNull();
    }
}
