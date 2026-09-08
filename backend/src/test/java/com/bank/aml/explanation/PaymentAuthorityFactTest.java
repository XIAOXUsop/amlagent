package com.bank.aml.explanation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G1-3 / RF-18 / RF-19：授权双时间规则。
 * 付款后撤销不否定付款时点有效性（RF-18）；付款后追认不替代付款前授权（RF-19）；
 * 撤销早于付款 → 付款时无效；权限未知保持 UNKNOWN。
 */
class PaymentAuthorityFactTest {

    private final PaymentAuthorityFactService service = new PaymentAuthorityFactService();
    private static final LocalDate PAYMENT_DATE = LocalDate.parse("2026-08-01");

    /** RF-18：授权付款时有效、今天到期/付款后撤销 → 历史付款不机械失效。 */
    @Test
    void revocationAfterPaymentDoesNotInvalidateHistoricPayment() {
        var validity = service.evaluateAtPayment(List.of(
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-01-01"), null, PAYMENT_DATE,
                        LocalDateTime.now(), "GRANTED"),
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-09-01"), null, PAYMENT_DATE,
                        LocalDateTime.now(), "REVOKED")), PAYMENT_DATE);
        assertThat(validity.validAtPayment()).isEqualTo("VALID");
        assertThat(validity.revokedAfterPayment()).isTrue();
        assertThat(validity.explanation()).contains("不影响付款时点有效性");
    }

    /** RF-18 反向：撤销生效日早于付款日 → 付款时无效。 */
    @Test
    void revocationBeforePaymentInvalidates() {
        var validity = service.evaluateAtPayment(List.of(
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-01-01"), null, PAYMENT_DATE,
                        LocalDateTime.now(), "GRANTED"),
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-07-01"), null, PAYMENT_DATE,
                        LocalDateTime.now(), "REVOKED")), PAYMENT_DATE);
        assertThat(validity.validAtPayment()).isEqualTo("INVALID");
    }

    /** RF-19：付款前无授权证据 + 付款后追认 → UNKNOWN（追认不伪装付款前授权）。 */
    @Test
    void ratificationAfterPaymentKeepsUnknown() {
        var validity = service.evaluateAtPayment(List.of(
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-08-15"), null, PAYMENT_DATE,
                        LocalDateTime.now(), "RATIFIED")), PAYMENT_DATE);
        assertThat(validity.validAtPayment()).isEqualTo("UNKNOWN");
        assertThat(validity.ratifiedAfterPayment()).isTrue();
        assertThat(validity.explanation()).contains("不自动证明付款当时已获得授权");
    }

    /** 付款前 GRANTED → VALID（正常授权路径）。 */
    @Test
    void grantedBeforePaymentIsValid() {
        var validity = service.evaluateAtPayment(List.of(
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-01-01"), null, PAYMENT_DATE,
                        LocalDateTime.now(), "GRANTED")), PAYMENT_DATE);
        assertThat(validity.validAtPayment()).isEqualTo("VALID");
    }

    /** 付款日未知 → 拒绝评估（不用系统获知时间代替业务时间）。 */
    @Test
    void unknownPaymentDateIsRejected() {
        assertThatThrownBy(() -> service.evaluateAtPayment(List.of(
                new PaymentAuthorityFactService.AuthorityFact("AU-01",
                        LocalDate.parse("2026-01-01"), null, null,
                        LocalDateTime.now(), "GRANTED")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能以系统获知时间代替业务时间");
    }
}
