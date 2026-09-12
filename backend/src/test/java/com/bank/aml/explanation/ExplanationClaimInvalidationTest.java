package com.bank.aml.explanation;

import com.bank.aml.TestClocks;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplanationClaimInvalidationTest {

    private final ExplanationClaimRepository claims = mock(ExplanationClaimRepository.class);

    private final ClaimEvidenceLinkRepository links = mock(ClaimEvidenceLinkRepository.class);

    private final EvidenceArtifactVersionRepository artifacts = mock(EvidenceArtifactVersionRepository.class);

    private final AlertExplanationUnitRepository units = mock(AlertExplanationUnitRepository.class);

    private final ExplanationFactInvalidationService invalidation = mock(ExplanationFactInvalidationService.class);

    private final ExplanationClaimService service = new ExplanationClaimService(claims, links, artifacts, units,
            invalidation, TestClocks.FIXED);

    @Test
    void claimChangeInvalidatesCurrentExplanationBasis() {
        when(units.findByIdAndCaseId(10L, 1L)).thenReturn(Optional.of(new AlertExplanationUnit()));
        AtomicReference<ExplanationClaim> stored = new AtomicReference<>();
        when(claims.findByCaseIdAndUnitIdOrderByIdAsc(1L, 10L))
            .thenAnswer(inv -> stored.get() == null ? List.of() : List.of(stored.get()));
        when(claims.save(any())).thenAnswer(inv -> {
            ExplanationClaim claim = inv.getArgument(0);
            setId(claim, 20L);
            stored.set(claim);
            return claim;
        });
        when(links.findByClaimIdOrderByIdAsc(20L)).thenReturn(List.of());

        service.declareClaims(1L, 10L, List.of(new ExplanationClaimService.ClaimDeclaration("C3", "CONTRADICTED",
                "DECISION_CRITICAL", "付款主体明确否认该笔付款存在有效授权", null, null, null, List.of())), "analyst");

        verify(invalidation).invalidate(eq(1L), contains("C3"), eq("analyst"));
    }

    @Test
    void foreignUnitCannotReceiveClaims() {
        when(units.findByIdAndCaseId(10L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.declareClaims(1L, 10L, List.of(), "analyst"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不属于当前案件");
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
