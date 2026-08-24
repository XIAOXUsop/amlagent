package com.bank.aml.sanction;

import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.risk.RiskFactAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SanctionMatchScorerTest {

    private final SanctionMatchScorer scorer = new SanctionMatchScorer();
    private final CustomerProfile customer = new CustomerProfile(
            "C001", "张伟", "110101198506123456", "自然人", "零售", "北京", "-");

    @Test
    void confirmsExactIdentityAndKeepsIdentityMasked() {
        var match = scorer.score(customer, new SanctionRecord(
                "ZHANG WEI（张伟）", "110101198506123456", "OFAC SDN", "reason", 1));

        assertThat(match.decision()).isEqualTo(SanctionMatchDecision.CONFIRMED);
        assertThat(match.score()).isEqualTo(100);
        assertThat(match.reasonCodes()).contains("IDENTITY_EXACT", "NAME_ALIAS_EXACT");
        assertThat(match.identityMasked()).doesNotContain("110101198506123456");
        assertThat(match.actionable()).isTrue();
    }

    @Test
    void dismissesCompanyWhoseNameOnlyContainsNaturalPersonName() {
        var match = scorer.score(customer, new SanctionRecord(
                "ZHANGWEI TRADING CO., LTD.（张伟国际贸易有限公司）", "", "OFAC SDN", "reason", 1));

        assertThat(match.decision()).isEqualTo(SanctionMatchDecision.DISMISSED);
        assertThat(match.score()).isLessThan(85);
        assertThat(match.reasonCodes()).contains("SUBJECT_TYPE_CONFLICT");
        assertThat(match.actionable()).isFalse();
    }

    @Test
    void identityConflictOverridesSameName() {
        var match = scorer.score(customer, new SanctionRecord(
                "张伟", "999999999999999999", "WATCHLIST", "reason", 2));

        assertThat(match.decision()).isEqualTo(SanctionMatchDecision.DISMISSED);
        assertThat(match.reasonCodes()).contains("IDENTITY_CONFLICT");
        assertThat(match.actionable()).isFalse();
    }

    @Test
    void exactNameWithoutIdentityRequiresReviewAndDoesNotBecomeConfirmedGuardrailFact() {
        var match = scorer.score(customer, new SanctionRecord("张伟", "", "WATCHLIST", "reason", 2));

        assertThat(match.decision()).isEqualTo(SanctionMatchDecision.REVIEW_REQUIRED);
        assertThat(match.score()).isEqualTo(88);
        assertThat(match.actionable()).isFalse();
    }

    @Test
    void riskAssemblerFiltersLowConfidenceCompanySubstringFromGuardrailFacts() {
        CustomerDataPort source = mock(CustomerDataPort.class);
        SanctionRecord person = new SanctionRecord(
                "ZHANG WEI（张伟）", "110101198506123456", "OFAC", "person", 1);
        SanctionRecord company = new SanctionRecord(
                "张伟国际贸易有限公司", "", "OFAC", "company", 1);
        when(source.searchSanctions("张伟")).thenReturn(List.of(company, person));
        when(source.searchSanctions("110101198506123456")).thenReturn(List.of(person));

        assertThat(new RiskFactAssembler(source, scorer).searchSanctions(customer))
                .containsExactly(person);
    }

    @Test
    void fingerprintSeparatesSameNameEntriesWhoseSourceFactsDiffer() {
        SanctionRecord first = new SanctionRecord("赵敏", "", "WATCHLIST", "case-A", 2);
        SanctionRecord second = new SanctionRecord("赵敏", "", "WATCHLIST", "case-B", 2);

        assertThat(scorer.score(customer, first).candidateFingerprint())
                .isNotEqualTo(scorer.score(customer, second).candidateFingerprint());
    }
}
