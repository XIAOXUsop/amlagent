package com.bank.aml.explanation;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一核验可采用性评估器（v4 计划 §4.1 / FR-01）。
 *
 * <p>核心语义：同一材料可以有面向不同事实的多个核验动作；核验链按
 * "材料版本 + 核验对象（questionCode/claim 事实键）+ 事件序号"组织，
 * 链内最新有效状态决定该事实是否获得肯定支持——不能取整条材料的最后事件代表所有事实，
 * 也不能让 Q1 的核验替 Q2 通过。
 *
 * <p>返回结构化 blocker（blockerCode + questionCode + artifactVersionId + remediation），
 * 供单元提交、readiness、复核预检和最终提交共同调用（§7.1：预检与提交使用同一评估函数）。
 */
@Service
public class EvidenceAdmissibilityService {

    /** 评估输入：一题（或一个 Claim）的材料引用集合。 */
    public record SubjectEvidence(String questionCode, List<Long> artifactVersionIds) {
    }

    /** 评估结果：该题是否具备可采用证据；不具备时给出结构化阻断。 */
    public record AdmissibilityResult(
            boolean admissible,
            String blockerCode,
            String questionCode,
            Long artifactVersionId,
            String remediation
    ) {
        public static AdmissibilityResult ok(String questionCode) {
            return new AdmissibilityResult(true, null, questionCode, null, null);
        }
    }

    private final EvidenceArtifactVersionRepository artifactRepository;
    private final EvidenceVerificationEventRepository verificationRepository;

    public EvidenceAdmissibilityService(EvidenceArtifactVersionRepository artifactRepository,
                                        EvidenceVerificationEventRepository verificationRepository) {
        this.artifactRepository = artifactRepository;
        this.verificationRepository = verificationRepository;
    }

    /**
     * 按题独立评估（不共享其它题的累积材料集合）：
     * <ol>
     *   <li>该题至少引用 1 份 RESOLVED 且完整性未破坏的材料；</li>
     *   <li>至少 1 份引用材料在"该题事实键"上有当前有效的 CONFIRMED 核验链；</li>
     *   <li>材料级通用核验（subjectFactKey=NULL）可作为兼容支持——前提是链内无更新的
     *       UNRESOLVED/MISMATCH 失去支持事件（§4.1：UNRESOLVED 不再提供该范围的肯定支持）。</li>
     * </ol>
     */
    public AdmissibilityResult assessQuestion(Long caseId, SubjectEvidence subject) {
        String questionCode = subject.questionCode();
        if (subject.artifactVersionIds() == null || subject.artifactVersionIds().isEmpty()) {
            return new AdmissibilityResult(false, "EVIDENCE_EMPTY", questionCode, null,
                    "请先抓取来源材料并在问题 " + questionCode + " 中引用（空引用不能形成可采用结论）");
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<String> unavailable = new ArrayList<>();
        List<Long> admissibleArtifacts = new ArrayList<>();
        for (Long artifactVersionId : new LinkedHashSet<>(subject.artifactVersionIds())) {
            if (!seen.add(artifactVersionId)) {
                continue;
            }
            EvidenceArtifactVersion artifact = artifactRepository.findByIdAndCaseId(artifactVersionId, caseId)
                    .orElse(null);
            if (artifact == null) {
                return new AdmissibilityResult(false, "ARTIFACT_FOREIGN", questionCode, artifactVersionId,
                        "问题 " + questionCode + " 引用的材料 " + artifactVersionId + " 不属于当前案件");
            }
            if ("MISMATCH".equals(artifact.getIntegrityStatus())) {
                return new AdmissibilityResult(false, "INTEGRITY_BLOCKED", questionCode, artifactVersionId,
                        "问题 " + questionCode + " 引用完整性不符（MISMATCH）的材料 "
                                + artifact.getArtifactKey() + "；需更正来源");
            }
            if (!"RESOLVED".equals(artifact.getAvailability())) {
                unavailable.add(artifact.getArtifactKey() + "(" + artifact.getAvailability() + ")");
                continue;
            }
            admissibleArtifacts.add(artifactVersionId);
        }
        if (!unavailable.isEmpty()) {
            return new AdmissibilityResult(false, "ARTIFACT_NOT_RESOLVED", questionCode,
                    subject.artifactVersionIds().get(0),
                    "问题 " + questionCode + " 引用的材料未取得内容：" + String.join("、", unavailable)
                            + "；需重试来源或更换替代来源");
        }
        // 按题事实键评估核验链：链内最新有效状态决定支持
        for (Long artifactVersionId : admissibleArtifacts) {
            SupportStatus status = effectiveSupport(artifactVersionId, questionCode);
            if (status == SupportStatus.CONFIRMED) {
                return AdmissibilityResult.ok(questionCode);
            }
            if (status == SupportStatus.LOST) {
                return new AdmissibilityResult(false, "VERIFICATION_LOST", questionCode, artifactVersionId,
                        "问题 " + questionCode + " 的核验已失去支持（UNRESOLVED/MISMATCH）；"
                                + "需重新核验该事实后才能形成可采用结论");
            }
        }
        return new AdmissibilityResult(false, "VERIFICATION_MISSING", questionCode,
                admissibleArtifacts.isEmpty() ? null : admissibleArtifacts.get(0),
                "问题 " + questionCode + " 引用的材料在" + questionCode + " 事实上尚无有效 CONFIRMED 核验；"
                        + "其它问题的核验不能替代本题（FR-01），请记录面向本事实的核验动作");
    }

    /** 该材料在指定事实上的最新有效支持状态。 */
    public SupportStatus effectiveSupport(Long artifactVersionId, String factKey) {
        List<EvidenceVerificationEvent> chain = new ArrayList<>();
        chain.addAll(verificationRepository
                .findByArtifactVersionIdAndSubjectFactKeyOrderByEventTimeAscIdAsc(artifactVersionId, factKey));
        chain.addAll(verificationRepository
                .findByArtifactVersionIdAndSubjectFactKeyIsNullOrderByEventTimeAscIdAsc(artifactVersionId));
        if (chain.isEmpty()) {
            return SupportStatus.NONE;
        }
        // 事件序号（eventTime,id）升序 = 链内最新状态；只关心最后一个事件的 result。
        EvidenceVerificationEvent latest = chain.get(chain.size() - 1);
        return switch (latest.getResult()) {
            case "CONFIRMED" -> SupportStatus.CONFIRMED;
            case "UNRESOLVED", "MISMATCH" -> SupportStatus.LOST;
            default -> SupportStatus.NONE;
        };
    }

    /** 事实支持状态。 */
    public enum SupportStatus {
        /** 无任何核验记录。 */
        NONE,
        /** 当前有效 CONFIRMED。 */
        CONFIRMED,
        /** 最新核验失去支持（UNRESOLVED/MISMATCH）。 */
        LOST
    }
}
