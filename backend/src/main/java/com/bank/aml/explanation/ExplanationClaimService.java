package com.bank.aml.explanation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 待验证事实 Claim 写入链（v4 计划 G1-2 / §5.2 / RF-20）。
 *
 * <p>一次声明落库 Claim 状态与 ClaimEvidenceLink（支持/反对、来源家族、定位）；
 * 提交时冻结 claim revision 与采用状态。同源家族（sourceSystem + 归一化 sourceReference）
 * 不能当作多份独立确认——独立确认计数按家族去重。
 */
@Service
public class ExplanationClaimService {

    private static final Set<String> CLAIM_CODES = Set.of("C1", "C2", "C3", "C4", "C5", "C6");
    private static final Set<String> STATUSES =
            Set.of("SUPPORTED", "CONTRADICTED", "UNRESOLVED", "NOT_APPLICABLE", "UNASSESSED");
    private static final Set<String> DIRECTIONS = Set.of("SUPPORTS", "CHALLENGES", "CONTEXT");

    private final ExplanationClaimRepository claimRepository;
    private final ClaimEvidenceLinkRepository linkRepository;
    private final EvidenceArtifactVersionRepository artifactRepository;

    public ExplanationClaimService(ExplanationClaimRepository claimRepository,
                                   ClaimEvidenceLinkRepository linkRepository,
                                   EvidenceArtifactVersionRepository artifactRepository) {
        this.claimRepository = claimRepository;
        this.linkRepository = linkRepository;
        this.artifactRepository = artifactRepository;
    }

    /** 声明请求：单条 Claim 状态 + 材料引用。 */
    public record ClaimDeclaration(
            String claimCode,
            String status,
            String importance,
            String judgement,
            String methodNote,
            String limitations,
            String notApplicableReason,
            List<LinkDeclaration> links
    ) {
    }

    public record LinkDeclaration(
            Long artifactVersionId,
            String direction,
            String location,
            String note
    ) {
    }

    /** 覆盖式声明单元的 C1~C6（未列出的 Claim 置 UNASSESSED 并保留历史关联）。 */
    @Transactional
    public List<ExplanationViews.ClaimView> declareClaims(Long caseId, Long unitId,
                                                          List<ClaimDeclaration> declarations,
                                                          String actor) {
        Set<String> declaredCodes = new LinkedHashSet<>();
        for (ClaimDeclaration declaration : declarations == null ? List.<ClaimDeclaration>of() : declarations) {
            String code = upper(declaration.claimCode());
            if (!CLAIM_CODES.contains(code)) {
                throw new IllegalArgumentException("Claim 编号不在 C1~C6：" + declaration.claimCode());
            }
            if (!declaredCodes.add(code)) {
                throw new IllegalArgumentException("Claim " + code + " 重复声明");
            }
            String status = upper(declaration.status());
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("Claim " + code + " 状态不在允许范围：" + declaration.status());
            }
            String judgement = declaration.judgement() == null ? "" : declaration.judgement().trim();
            if (!"UNASSESSED".equals(status) && judgement.length() < 10) {
                throw new IllegalArgumentException("Claim " + code + " 的判断需至少 10 个字符"
                        + "（为什么证据支持/反对/不足以判断）");
            }
            if ("NOT_APPLICABLE".equals(status)
                    && (declaration.notApplicableReason() == null
                    || declaration.notApplicableReason().trim().length() < 10)) {
                throw new IllegalArgumentException("Claim " + code + " 标记不适用需说明理由与适用条件");
            }
            ExplanationClaim claim = claimRepository
                    .findByCaseIdAndUnitIdOrderByIdAsc(caseId, unitId).stream()
                    .filter(existing -> code.equals(existing.getClaimCode()))
                    .findFirst()
                    .orElseGet(() -> {
                        ExplanationClaim created = new ExplanationClaim();
                        created.setCaseId(caseId);
                        created.setUnitId(unitId);
                        created.setClaimCode(code);
                        created.setClaimRevision(0);
                        created.setCreatedAt(LocalDateTime.now());
                        return created;
                    });
            claim.setStatus(status);
            claim.setImportance(declaration.importance() == null || declaration.importance().isBlank()
                    ? "DECISION_CRITICAL" : declaration.importance().trim());
            claim.setJudgement(judgement);
            claim.setMethodNote(declaration.methodNote());
            claim.setLimitations(declaration.limitations());
            claim.setNotApplicableReason(declaration.notApplicableReason());
            claim.setClaimRevision(claim.getClaimRevision() + 1);
            claim.setUpdatedBy(actor);
            claim.setUpdatedAt(LocalDateTime.now());
            ExplanationClaim saved = claimRepository.save(claim);

            // 关联重写（覆盖式）：校验材料归属并落 sourceFamily
            linkRepository.findByClaimIdOrderByIdAsc(saved.getId())
                    .forEach(linkRepository::delete);
            for (LinkDeclaration link : declaration.links() == null ? List.<LinkDeclaration>of() : declaration.links()) {
                EvidenceArtifactVersion artifact = artifactRepository
                        .findByIdAndCaseId(link.artifactVersionId(), caseId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Claim " + code + " 引用的材料不属于当前案件：" + link.artifactVersionId()));
                String direction = upper(link.direction());
                if (!DIRECTIONS.contains(direction)) {
                    throw new IllegalArgumentException("Claim " + code + " 关联方向不在允许范围：" + link.direction());
                }
                ClaimEvidenceLink entity = new ClaimEvidenceLink();
                entity.setCaseId(caseId);
                entity.setClaimId(saved.getId());
                entity.setArtifactVersionId(link.artifactVersionId());
                entity.setDirection(direction);
                entity.setSourceFamily(sourceFamilyOf(artifact));
                entity.setLocation(link.location());
                entity.setNote(link.note());
                entity.setCreatedBy(actor);
                entity.setCreatedAt(LocalDateTime.now());
                linkRepository.save(entity);
            }
        }
        return viewAll(caseId, unitId);
    }

    /** RF-20：独立确认计数按来源家族去重——同源复制/转发/摘要不增加独立数。 */
    @Transactional(readOnly = true)
    public int independentSourceCount(Long claimId) {
        Set<String> families = new LinkedHashSet<>();
        for (ClaimEvidenceLink link : linkRepository.findByClaimIdOrderByIdAsc(claimId)) {
            if ("SUPPORTS".equals(link.getDirection()) && link.getSourceFamily() != null) {
                families.add(link.getSourceFamily());
            }
        }
        return families.size();
    }

    @Transactional(readOnly = true)
    public List<ExplanationViews.ClaimView> viewAll(Long caseId, Long unitId) {
        Map<Long, Integer> independent = new LinkedHashMap<>();
        List<ExplanationViews.ClaimView> views = new java.util.ArrayList<>();
        for (ExplanationClaim claim : claimRepository.findByCaseIdAndUnitIdOrderByIdAsc(caseId, unitId)) {
            List<ClaimEvidenceLink> links = linkRepository.findByClaimIdOrderByIdAsc(claim.getId());
            views.add(new ExplanationViews.ClaimView(claim.getId(), claim.getClaimCode(), claim.getStatus(),
                    claim.getImportance(), claim.getJudgement(), claim.getMethodNote(),
                    claim.getLimitations(), claim.getNotApplicableReason(), claim.getClaimRevision(),
                    claim.getUpdatedBy(),
                    links.stream().map(link -> new ExplanationViews.ClaimLinkView(link.getId(),
                            link.getArtifactVersionId(), link.getDirection(), link.getSourceFamily(),
                            link.getLocation(), link.getNote())).toList(),
                    independentSourceCount(claim.getId())));
        }
        return views;
    }

    /** 来源家族：sourceSystem + 归一化 sourceReference（同文件复制/转发同家族）。 */
    public static String sourceFamilyOf(EvidenceArtifactVersion artifact) {
        return (artifact.getSourceSystem() + ":" + artifact.getSourceReference())
                .toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
