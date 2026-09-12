package com.bank.aml.common;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.TooManyRequestsException;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/** W1/V2-01~03：调查版本冲突的 HTTP 协议映射 —— 409 + 稳定错误码 + 有界冲突对象。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC));

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
        assertThat(response.getBody().message()).isEqualTo("调查事实已发生变化，请刷新后重新确认");
        assertThat(response.getBody().message()).doesNotContain("当前版本");
        assertThat(response.getBody().conflict()).isNotNull();
        assertThat(response.getBody().conflict().type()).isEqualTo("HYPOTHESIS");
        assertThat(response.getBody().conflict().id()).isEqualTo(31L);
        assertThat(response.getBody().conflict().currentVersion()).isEqualTo(4);
    }

    /** 未分类内部状态不得透传原始异常消息。 */
    @Test
    void internalStateFailureIsSanitized() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler
            .handleIllegalState(new IllegalStateException("C:\\secret\\rag.json"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("服务器内部错误");
        assertThat(response.getBody().message()).doesNotContain("secret");
        assertThat(response.getBody().conflict()).isNull();
    }

    @Test
    void illegalArgumentMessageIsSanitized() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler.handleIllegalArgument(
                new IllegalArgumentException("Unexpected token at /srv/private/payload.json"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("请求参数不合法");
        assertThat(response.getBody().message()).doesNotContain("payload.json");
    }

    @Test
    void domainExceptionMessagesAreSanitizedAtHttpBoundary() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ApiError workflowError = handler
            .handleStateConflict(new WorkflowStateConflictException(1L, CaseStatus.PENDING, Set.of(CaseStatus.DONE)),
                    request)
            .getBody();
        ApiError rateLimitError = handler.handleRateLimited(new TooManyRequestsException("user=alice"), request)
            .getBody();
        ApiError businessError = handler
            .handleNonRetryable(new NonRetryableWorkflowException("/srv/private/model.json"), request)
            .getBody();

        assertThat(workflowError).isNotNull();
        assertThat(workflowError.message()).isEqualTo("工单状态已发生变化，请刷新后重试");
        assertThat(rateLimitError).isNotNull();
        assertThat(rateLimitError.message()).isEqualTo("请求过于频繁，请稍后再试");
        assertThat(rateLimitError.message()).doesNotContain("alice");
        assertThat(businessError).isNotNull();
        assertThat(businessError.message()).isEqualTo("业务请求无法处理，请检查输入后重试");
        assertThat(businessError.message()).doesNotContain("model.json");
    }

    @Test
    void queryAndMethodValidationFailuresMapToStable400Contract() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler
            .handleRequestContractViolation(new ConstraintViolationException(Set.of()), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("请求参数校验失败");
    }

    @Test
    void authenticationFailureMapsToStable401WithoutLeakingAccountState() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<ApiError> response = handler
            .handleAuthenticationFailure(new BadCredentialsException("disabled user alice"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.getBody().message()).isEqualTo("用户名或密码错误");
        assertThat(response.getBody().message()).doesNotContain("alice", "disabled");
    }

}
