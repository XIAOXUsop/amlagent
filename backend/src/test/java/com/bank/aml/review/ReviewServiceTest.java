package com.bank.aml.review;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private final CaseRepository caseRepository = mock(CaseRepository.class);
    private final ManualReviewRepository reviewRepository = mock(ManualReviewRepository.class);
    private final ReviewService service = new ReviewService(caseRepository, reviewRepository);

    @Test
    void submitThrowsConflictWhenNotHold() {
        CaseEntity done = new CaseEntity();
        done.setStatus(CaseStatus.DONE);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "APPROVE", "同意", 0))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void submitThrowsConflictWhenStaleRevision() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(hold));
        // 旧 revision：条件更新返回 0，并发下已被其他复核员处理
        when(caseRepository.completeReview(eq(1L), eq(CaseStatus.DONE), eq(CaseStatus.HOLD),
                eq(0), nullable(String.class), nullable(String.class))).thenReturn(0);

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "APPROVE", "同意", 0))
                .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void submitThrowsConflictOnIllegalDecision() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "UNKNOWN", "x", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
