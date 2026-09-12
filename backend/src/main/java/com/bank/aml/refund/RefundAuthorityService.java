package com.bank.aml.refund;

import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 退款收款权限事实与影响评估（v4 计划 §6.3 / G2-2 / RF-06~10）。
 *
 * <p>
 * 核心语义：
 * <ul>
 * <li>收款权限不得只用"和原付款人名称相似"判断——使用主体及账户归属核验结论；</li>
 * <li>原路退款不自动免责（RF-07）：退款商业原因未解决时不能仅凭原路排除；</li>
 * <li>三分支：退给 P（原路）/ 退给 B（买方，需权限核实）/ 退给 D（无关，权限未知阻断）；</li>
 * <li>影响评估四类：正常后续记录 / 需补充核验 / 否定旧支持事实 / 来源更正； 未完成匹配时禁止标记"无影响"。</li>
 * </ul>
 */
@Service
public class RefundAuthorityService {

    /** 收款权限核验结论（由主体/账户归属核验产生，不由名称相似推断）。 */
    public enum RecipientAuthority {

        /** 退给原付款主体 P（账户归属已核验一致）。 */
        ORIGINAL_PAYER_VERIFIED,
        /** 退给合同买方 B，且 B 的收款权限已独立核实（合同/P-B 结算安排）。 */
        BUYER_VERIFIED,
        /** 退给 B 或 D，权限未知或被否认。 */
        UNRESOLVED,
        /** 收款权限被明确否认/矛盾。 */
        CONTRADICTED

    }

    /** 商业原因核验结论。 */
    public enum CommercialReason {

        /** 退货/解除依据已核验（退货单、数量计价、解除协议）。 */
        VERIFIED,
        /** 只有口头/客户声明，无独立依据。 */
        UNVERIFIED,
        /** 未知/未提供。 */
        MISSING

    }

    /** 退款解释可提交性评估结果。 */
    public record RefundAdmissibility(boolean admissible, String blockerCode, String explanation) {
    }

    /**
     * 退款解释门禁（§6.3 决策表）： 权限 ORIGINAL_PAYER_VERIFIED/BUYER_VERIFIED + 商业原因 VERIFIED → 可提交；
     * 原路但原因缺失（RF-07）→ 原因未解决阻断； 权限 UNRESOLVED（RF-09）/CONTRADICTED（RF-10）→ 阻断解释成立与依赖排除；
     * "没有证据解释"不等于"已有证据证实可疑"——阻断不产生自动 SUSPICIOUS。
     */
    public RefundAdmissibility assess(RecipientAuthority authority, CommercialReason reason) {
        boolean reasonOk = reason == CommercialReason.VERIFIED;
        return switch (authority) {
            case ORIGINAL_PAYER_VERIFIED -> reasonOk ? new RefundAdmissibility(true, null, "原路退款且商业原因已核验；可提交退款解释")
                    : new RefundAdmissibility(false, "REFUND_REASON_UNRESOLVED", "原路退款不自动免责（RF-07）：退款商业原因未解决，不能仅凭原路排除");
            case BUYER_VERIFIED -> reasonOk ? new RefundAdmissibility(true, null, "买方收款权限已核实且商业原因成立；可进入人工解释复核（RF-08）")
                    : new RefundAdmissibility(false, "REFUND_REASON_UNRESOLVED", "退款商业原因未核验；权限与原因需分别成立（RF-08）");
            case UNRESOLVED -> new RefundAdmissibility(false, "RECIPIENT_AUTHORITY_UNRESOLVED",
                    "退款收款权限未知（RF-09）：同集团/买方身份本身不足以证明收款权限，" + "需主体与账户归属核验；阻断退款解释成立");
            case CONTRADICTED -> new RefundAdmissibility(false, "RECIPIENT_AUTHORITY_CONTRADICTED",
                    "收款权限被否认/矛盾（RF-10）：保留反证与冲突，不覆盖旧授权原文；" + "是否形成怀疑由复核员结合证据决定");
        };
    }

    /** 影响评估类型（§6.4）。 */
    public enum ImpactType {

        /** 只需记录的正常后续事件。 */
        INFORMATIONAL,
        /** 需要补充核验（新义务）。 */
        SUPPLEMENT_REQUIRED,
        /** 否定旧支持事实（相关采用关系失效）。 */
        CONTRADICTS_ADOPTED,
        /** 来源更正/撤回。 */
        SOURCE_CORRECTED

    }

    /** 影响评估输入。 */
    public record ImpactInput(RecipientAuthority authority, CommercialReason reason, boolean overAllocation,
            boolean scopeGap, boolean verificationLost) {
    }

    /**
     * 确定性影响评估（RefundEvent → 关联原分配 → 依赖事实 → 影响）。 未完成匹配时禁止"无影响"——四个类型至少落一个。
     */
    public ImpactType assessImpact(ImpactInput input) {
        if (input.verificationLost()) {
            return ImpactType.CONTRADICTS_ADOPTED;
        }
        if (input.overAllocation() || input.scopeGap()) {
            return ImpactType.SOURCE_CORRECTED;
        }
        RefundAdmissibility admissibility = assess(input.authority(), input.reason());
        if (admissibility.admissible()) {
            return ImpactType.INFORMATIONAL;
        }
        return ImpactType.SUPPLEMENT_REQUIRED;
    }

    /** 三分支场景语义表（固定案例 §3.2；供测试与页面文案共用）。 */
    public static Map<String, String> branchSemantics() {
        return Map.of("ORIGINAL_PATH", "正常原路退款：新事件解释可提交复核；R1 原文不改写", "TO_BUYER",
                "退给合同买方：先核实 B 收款权限及 P/B/A 结算安排；身份本身不足以证明权限", "TO_UNRELATED", "退给无关账户：形成权限/用途未解决事项；禁止自动沿用原排除结论");
    }

}
