package com.bank.aml.sanction;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 召回名单候选并生成按置信度排序的可解释筛查结果。 */
@Service
public class SanctionScreeningService {

    private static final Set<String> REVIEW_DECISIONS = Set.of("CONFIRM", "DISMISS", "REQUEST_MORE_INFO");
    private static final int MAX_COMMENT_LENGTH = 500;

    private final CustomerDataPort dataSource;
    private final SanctionMatchScorer scorer;
    private final SanctionCandidateReviewRepository reviewRepository;

    public SanctionScreeningService(CustomerDataPort dataSource, SanctionMatchScorer scorer) {
        this(dataSource, scorer, null);
    }

    @Autowired
    public SanctionScreeningService(CustomerDataPort dataSource, SanctionMatchScorer scorer,
                                    SanctionCandidateReviewRepository reviewRepository) {
        this.dataSource = dataSource;
        this.scorer = scorer;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public SanctionScreeningResult screen(String customerId) {
        CustomerProfile customer = dataSource.findCustomer(customerId)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在：" + customerId));
        List<ScoredCandidate> scored = scoreCandidates(customer);
        List<SanctionCandidateMatch> matches = scored.stream().map(ScoredCandidate::match).toList();
        String status = matches.stream().anyMatch(m -> m.decision() == SanctionMatchDecision.CONFIRMED)
                ? "CONFIRMED_MATCH"
                : matches.stream().anyMatch(m -> m.decision() == SanctionMatchDecision.REVIEW_REQUIRED)
                ? "REVIEW_REQUIRED" : "NO_MATCH";
        return new SanctionScreeningResult(customer.id(), customer.name(), status, dataSource.asOfTime(),
                dataSource.sourceSystem(), dataSource.sourceVersion(), matches);
    }

    /** Guardrail 只接收算法确定命中或经人工确认的候选；待核验候选不再伪装成确定命中。 */
    @Transactional(readOnly = true)
    public List<SanctionRecord> actionableRecords(CustomerProfile customer) {
        return scoreCandidates(customer).stream()
                .filter(candidate -> candidate.match().actionable())
                .map(ScoredCandidate::record)
                .toList();
    }

    /** 追加候选核验版本；旧 revision 或并发重复 revision 返回 409。 */
    @Transactional
    public SanctionScreeningResult review(String customerId, String candidateFingerprint, String decision,
                                           String comment, int expectedRevision, String reviewerId) {
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase();
        if (!REVIEW_DECISIONS.contains(normalizedDecision)) {
            throw new IllegalArgumentException("非法候选复核决定：" + decision);
        }
        String normalizedComment = comment == null || comment.isBlank() ? null : comment.trim();
        if (normalizedComment == null) {
            throw new IllegalArgumentException("候选人工核验必须填写判断依据");
        }
        if (normalizedComment != null && normalizedComment.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("核验意见过长（最多 500 字）");
        }
        if (expectedRevision < 0) throw new IllegalArgumentException("候选复核版本不能为负数");

        CustomerProfile customer = dataSource.findCustomer(customerId)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在：" + customerId));
        ScoredCandidate candidate = scoreCandidates(customer).stream()
                .filter(item -> item.match().candidateFingerprint().equals(candidateFingerprint))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("名单候选不存在或数据源已更新，请重新筛查"));
        int currentRevision = candidate.match().reviewRevision();
        if (currentRevision != expectedRevision) {
            throw new SanctionReviewConflictException(
                    "候选已被其他复核人更新，当前版本为 " + currentRevision + "，请刷新后重试");
        }

        SanctionCandidateReview review = new SanctionCandidateReview();
        review.setCustomerId(customerId);
        review.setCandidateFingerprint(candidateFingerprint);
        review.setCandidateName(candidate.record().name());
        review.setListType(candidate.record().listType());
        review.setMatchScore(candidate.match().score());
        review.setAlgorithmDecision(candidate.match().algorithmDecision().name());
        review.setReviewDecision(normalizedDecision);
        review.setReviewerId(reviewerId);
        review.setComment(normalizedComment);
        review.setReviewRevision(currentRevision + 1);
        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException ex) {
            throw new SanctionReviewConflictException("候选复核版本发生并发冲突，请刷新后重试");
        }
        return screen(customerId);
    }

    public List<SanctionRecord> recall(CustomerProfile customer) {
        List<SanctionRecord> recalled = new ArrayList<>(dataSource.searchSanctions(customer.name()));
        if (customer.idCard() != null && !customer.idCard().isBlank()) {
            recalled.addAll(dataSource.searchSanctions(customer.idCard()));
        }
        return recalled.stream().distinct().toList();
    }

    private List<ScoredCandidate> scoreCandidates(CustomerProfile customer) {
        Map<String, SanctionCandidateReview> latest = latestReviews(customer.id());
        return recall(customer).stream()
                .map(record -> {
                    SanctionCandidateMatch match = scorer.score(customer, record);
                    return new ScoredCandidate(record, match.withReview(latest.get(match.candidateFingerprint())));
                })
                .sorted(Comparator.comparingInt((ScoredCandidate item) -> item.match().score()).reversed()
                        .thenComparing(item -> item.match().candidateName()))
                .toList();
    }

    private Map<String, SanctionCandidateReview> latestReviews(String customerId) {
        if (reviewRepository == null) return Map.of();
        return reviewRepository.findByCustomerIdOrderByCreatedAtAsc(customerId).stream()
                .collect(Collectors.toMap(SanctionCandidateReview::getCandidateFingerprint, Function.identity(),
                        (left, right) -> left.getReviewRevision() >= right.getReviewRevision() ? left : right));
    }

    private record ScoredCandidate(SanctionRecord record, SanctionCandidateMatch match) {
    }
}
