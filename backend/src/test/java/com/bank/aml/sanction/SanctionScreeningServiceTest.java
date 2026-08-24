package com.bank.aml.sanction;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class SanctionScreeningServiceTest {

    @Test
    void ranksCandidatesAndDoesNotTurnCompanySubstringIntoConfirmedHit() {
        CustomerDataPort source = mock(CustomerDataPort.class);
        CustomerProfile customer = new CustomerProfile(
                "C001", "张伟", "110101198506123456", "自然人", "零售", "北京", "-");
        SanctionRecord person = new SanctionRecord(
                "ZHANG WEI（张伟）", "110101198506123456", "OFAC", "person", 1);
        SanctionRecord company = new SanctionRecord(
                "张伟国际贸易有限公司", "", "OFAC", "company", 1);
        when(source.findCustomer("C001")).thenReturn(Optional.of(customer));
        when(source.searchSanctions("张伟")).thenReturn(List.of(company, person));
        when(source.searchSanctions("110101198506123456")).thenReturn(List.of(person));
        when(source.sourceSystem()).thenReturn("TEST");
        when(source.sourceVersion()).thenReturn("v1");

        var result = new SanctionScreeningService(source, new SanctionMatchScorer()).screen("C001");

        assertThat(result.status()).isEqualTo("CONFIRMED_MATCH");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().getFirst().decision()).isEqualTo(SanctionMatchDecision.CONFIRMED);
        assertThat(result.candidates().getLast().decision()).isEqualTo(SanctionMatchDecision.DISMISSED);
    }

    @Test
    void rejectsUnknownCustomer() {
        CustomerDataPort source = mock(CustomerDataPort.class);
        when(source.findCustomer("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new SanctionScreeningService(source, new SanctionMatchScorer()).screen("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("客户不存在");
    }

    @Test
    void persistsAppendOnlyReviewAndAppliesHumanDecisionToEffectiveResult() {
        CustomerDataPort source = mock(CustomerDataPort.class);
        SanctionCandidateReviewRepository reviews = mock(SanctionCandidateReviewRepository.class);
        CustomerProfile customer = new CustomerProfile(
                "C009", "赵敏", "", "自然人", "零售", "上海", "-");
        SanctionRecord candidate = new SanctionRecord("赵敏", "", "WATCHLIST", "same name", 2);
        List<SanctionCandidateReview> stored = new ArrayList<>();
        when(source.findCustomer("C009")).thenReturn(Optional.of(customer));
        when(source.searchSanctions("赵敏")).thenReturn(List.of(candidate));
        when(reviews.findByCustomerIdOrderByCreatedAtAsc("C009")).thenAnswer(invocation -> List.copyOf(stored));
        when(reviews.saveAndFlush(any())).thenAnswer(invocation -> {
            SanctionCandidateReview review = invocation.getArgument(0);
            review.onCreate();
            stored.add(review);
            return review;
        });
        SanctionScreeningService service = new SanctionScreeningService(source, new SanctionMatchScorer(), reviews);
        String fingerprint = service.screen("C009").candidates().getFirst().candidateFingerprint();

        SanctionScreeningResult result = service.review(
                "C009", fingerprint, "CONFIRM", "已补充出生日期并核验", 0, "reviewer");

        assertThat(result.status()).isEqualTo("CONFIRMED_MATCH");
        assertThat(result.candidates().getFirst().decision()).isEqualTo(SanctionMatchDecision.CONFIRMED);
        assertThat(result.candidates().getFirst().algorithmDecision()).isEqualTo(SanctionMatchDecision.REVIEW_REQUIRED);
        assertThat(result.candidates().getFirst().reviewRevision()).isEqualTo(1);
        assertThat(result.candidates().getFirst().reviewedBy()).isEqualTo("reviewer");
        assertThat(service.actionableRecords(customer)).containsExactly(candidate);

        SanctionScreeningResult dismissed = service.review(
                "C009", fingerprint, "DISMISS", "证件号码核验后确认不是同一主体", 1, "reviewer2");
        assertThat(dismissed.status()).isEqualTo("NO_MATCH");
        assertThat(dismissed.candidates().getFirst().reviewRevision()).isEqualTo(2);
        assertThat(service.actionableRecords(customer)).isEmpty();

        assertThatThrownBy(() -> service.review("C009", fingerprint, "CONFIRM", "stale", 1, "other"))
                .isInstanceOf(SanctionReviewConflictException.class)
                .hasMessageContaining("当前版本为 2");
    }

    @Test
    void requiresCommentForEveryHumanDecision() {
        CustomerDataPort source = mock(CustomerDataPort.class);
        SanctionCandidateReviewRepository reviews = mock(SanctionCandidateReviewRepository.class);
        SanctionScreeningService service = new SanctionScreeningService(source, new SanctionMatchScorer(), reviews);

        assertThatThrownBy(() -> service.review("C009", "x".repeat(64),
                "REQUEST_MORE_INFO", " ", 0, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须填写判断依据");
    }
}
