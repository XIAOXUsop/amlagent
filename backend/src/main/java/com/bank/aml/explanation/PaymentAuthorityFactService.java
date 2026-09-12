package com.bank.aml.explanation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 授权事实与双时间规则（v4 计划 G1-3 / §5.3 / RF-18 / RF-19）。
 *
 * <p>
 * 每个授权事实分别记录：业务发生/生效时间 effectiveAt、系统得知时间 recordedAt、来源版本。 核心规则：
 * <ul>
 * <li>RF-18：授权覆盖校验使用付款发生时点——付款后才撤销，不否定"付款时已有效"（不自动否定 R1）； 后续新退款是否需要新权限另行评估。</li>
 * <li>RF-19：付款后上传的追认文件 → "事后确认"单独标记，不用上传日代替授权生效日； 付款前权限未知保持待核验，不自动证明付款当时已授权。</li>
 * <li>撤销时间、追认时间与系统获知时间不混为一谈；未知时间显式为空并附原因。</li>
 * </ul>
 */
@Service
public class PaymentAuthorityFactService {

    /** 授权事实声明（由草稿 authority 结构或 Claim 事实键承载；本服务做时间规则评估）。 */
    public record AuthorityFact(String authorityRef,
            /** 授权生效日（业务时间；未知为 null 并附原因）。 */
            LocalDate effectiveFrom,
            /** 授权失效/撤销日（业务时间；未知为 null）。 */
            LocalDate effectiveTo,
            /** 付款（交易）发生日。 */
            LocalDate paymentDate,
            /** 系统得知该事实的时间（recordedAt）。 */
            LocalDateTime recordedAt,
            /** 事实类型：GRANTED / REVOKED / RATIFIED（付款后追认）。 */
            String factType) {
    }

    /** 评估结果：付款时点有效性 + 后续权限口径分离。 */
    public record AuthorityValidity(
            /** 付款发生时授权是否有效（UNKNOWN=权限未知，保持待核验）。 */
            String validAtPayment,
            /** 付款后授权是否已被撤销（独立于付款时点）。 */
            boolean revokedAfterPayment,
            /** 事后追认（付款后上传）标记。 */
            boolean ratifiedAfterPayment, String explanation) {
    }

    /**
     * 评估授权在付款时点的有效性（RF-18/RF-19 主规则）。
     *
     * <p>
     * 事实序列按 recordedAt 排序不代表业务顺序：effectiveFrom/effectiveTo 才是业务时间。 REVOKED
     * 的生效日（effectiveFrom=撤销生效日）在付款日之后 → 付款时仍有效； 撤销生效日 ≤ 付款日 → 付款时已无效。RATIFIED
     * 不改变付款时点的评估（保持 UNKNOWN/原状态）。
     */
    public AuthorityValidity evaluateAtPayment(List<AuthorityFact> facts, LocalDate paymentDate) {
        if (paymentDate == null) {
            throw new IllegalArgumentException("付款发生日未知：不能以系统获知时间代替业务时间评估授权（§5.3）");
        }
        boolean revokedAfterPayment = false;
        boolean revokedAtOrBeforePayment = false;
        boolean grantedBeforePayment = false;
        boolean grantExpiredAtPayment = false;
        boolean ratifiedAfterPayment = false;
        String grantExplanation = null;

        for (AuthorityFact fact : facts == null ? List.<AuthorityFact>of() : facts) {
            switch (fact.factType() == null ? "" : fact.factType().toUpperCase()) {
                case "GRANTED" -> {
                    if (fact.effectiveFrom() != null && !fact.effectiveFrom().isAfter(paymentDate)) {
                        if (fact.effectiveTo() != null && fact.effectiveTo().isBefore(paymentDate)) {
                            grantExpiredAtPayment = true;
                        }
                        else {
                            grantedBeforePayment = true;
                            grantExplanation = "授权 " + fact.authorityRef() + " 于 " + fact.effectiveFrom() + " 生效，付款日 "
                                    + paymentDate + " 时仍在有效期内";
                        }
                    }
                }
                case "REVOKED" -> {
                    // 撤销的业务生效日（effectiveFrom）决定它影响哪一段
                    if (fact.effectiveFrom() != null && fact.effectiveFrom().isAfter(paymentDate)) {
                        revokedAfterPayment = true;
                    }
                    else if (fact.effectiveFrom() != null) {
                        revokedAtOrBeforePayment = true;
                    }
                }
                case "RATIFIED" -> ratifiedAfterPayment = true;
                default -> {
                    /* 未知事实类型不参与评估 */ }
            }
        }

        if (revokedAtOrBeforePayment) {
            return new AuthorityValidity("INVALID", revokedAtOrBeforePayment, ratifiedAfterPayment,
                    "授权在付款日 " + paymentDate + " 或之前已被撤销；付款时无效（RF-18 反向：撤销早于付款）");
        }
        if (!grantedBeforePayment && grantExpiredAtPayment) {
            return new AuthorityValidity("INVALID", revokedAfterPayment, ratifiedAfterPayment,
                    "已取得的授权在付款日 " + paymentDate + " 前已过期；付款时无有效授权");
        }
        if (grantedBeforePayment) {
            String suffix = revokedAfterPayment ? "；付款后授权被撤销，不影响付款时点有效性（RF-18），" + "后续新付款/退款是否需要新权限另行评估" : "";
            return new AuthorityValidity("VALID", revokedAfterPayment, ratifiedAfterPayment,
                    (grantExplanation == null ? "" : grantExplanation) + suffix);
        }
        // 付款前无 GRANTED 事实：权限未知；追认文件不能补足付款时点（RF-19）
        return new AuthorityValidity("UNKNOWN", revokedAfterPayment, ratifiedAfterPayment,
                "付款前授权生效证据缺失，权限未知；付款后上传的追认文件属于事后确认（RF-19），" + "不自动证明付款当时已获得授权，保持待核验");
    }

}
