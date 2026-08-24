package com.bank.aml.rag;

import com.bank.aml.datasource.entity.LegalIndexStateEntity;
import com.bank.aml.datasource.entity.RagIndexManifestEntity;
import com.bank.aml.datasource.repository.LegalIndexStateRepository;
import com.bank.aml.datasource.repository.RagIndexManifestRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalIndexVersionServiceTest {

    private final LegalIndexStateRepository states = mock(LegalIndexStateRepository.class);
    private final RagIndexManifestRepository manifests = mock(RagIndexManifestRepository.class);
    private final LegalIndexVersionService service = new LegalIndexVersionService(states, manifests);

    @Test
    void cleanupTransitionCannotMarkCurrentActiveVersionAsPurging() {
        LegalIndexStateEntity state = new LegalIndexStateEntity();
        state.setActiveVersion("active");
        state.setPreviousVersion("previous");
        when(states.findForUpdate("legal")).thenReturn(Optional.of(state));

        assertThat(service.markPurgingIfSafe("active", Set.of())).isFalse();
        verify(manifests, never()).findById("active");
    }

    @Test
    void rollbackMovesPointerAndManifestStatusesUnderTheSameStateLock() {
        LegalIndexStateEntity state = new LegalIndexStateEntity();
        state.setActiveVersion("old");
        RagIndexManifestEntity old = manifest("old", "ACTIVE", 3);
        RagIndexManifestEntity target = manifest("target", "RETIRED", 7);
        when(states.findForUpdate("legal")).thenReturn(Optional.of(state));
        when(manifests.findById("target")).thenReturn(Optional.of(target));
        when(manifests.findById("old")).thenReturn(Optional.of(old));

        assertThat(service.rollback("target")).isTrue();

        assertThat(state.getActiveVersion()).isEqualTo("target");
        assertThat(state.getPreviousVersion()).isEqualTo("old");
        assertThat(state.getSegmentCount()).isEqualTo(7);
        assertThat(old.getStatus()).isEqualTo("RETIRED");
        assertThat(target.getStatus()).isEqualTo("ACTIVE");
    }

    private RagIndexManifestEntity manifest(String version, String status, int segments) {
        RagIndexManifestEntity entity = new RagIndexManifestEntity();
        entity.setIndexVersion(version);
        entity.setStatus(status);
        entity.setSegmentCount(segments);
        return entity;
    }
}
