package com.bank.aml.explanation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两种业务配方与适用性（v2 计划 §4）。
 * 配方选择是有依据的人工判断：分析员在草稿中声明经营角色/付款阶段/付款主体一致性，
 * 服务端按配方适用条件核定；不适用返回 POLICY_NOT_APPLICABLE，保留原始线索，不自动切宽松配方。
 */
@Component
public class ExplanationPolicyCatalog {

    public static final String GOODS_SETTLED_V1 = "GOODS_SETTLED_V1";
    public static final String GOODS_PREPAY_V1 = "GOODS_PREPAY_V1";

    /** 配方不适用错误码（API §13 阻断结构复用）。 */
    public static final String POLICY_NOT_APPLICABLE = "POLICY_NOT_APPLICABLE";

    /** 草稿声明的付款阶段闭集。 */
    public static final String STAGE_DELIVERED_SETTLEMENT = "DELIVERED_SETTLEMENT";
    public static final String STAGE_ADVANCE_PAYMENT = "ADVANCE_PAYMENT";
    private static final Set<String> SUPPORTED_STAGES = Set.of(
            STAGE_DELIVERED_SETTLEMENT, STAGE_ADVANCE_PAYMENT);

    /** 草稿声明字段（v2 计划 §4 先核对经营角色、付款阶段、付款主体）。 */
    public record Applicability(
            String businessRole,
            String paymentStage,
            boolean payerMatchesContractBuyer,
            boolean payeeMatchesContractSeller,
            String contractNumber,
            String deliveryDueDate
    ) {
    }

    /** 核定结果：适用配方或不适用的业务原因。 */
    public record Resolution(String policyCode, String notApplicableReason) {
        public boolean applicable() {
            return policyCode != null;
        }
    }

    /** 核定配方适用性；只做闭集与前提检查，不推断业务真实性。 */
    public Resolution resolve(Applicability input) {
        if (input == null) {
            return new Resolution(null, "缺少经营角色/付款阶段/付款主体核对信息，无法核定适用配方");
        }
        String role = trim(input.businessRole());
        if (role.length() < 4) {
            return new Resolution(null, "经营角色说明过短，不能据此认定货款配方");
        }
        String stage = trim(input.paymentStage()).toUpperCase(Locale.ROOT);
        if (!SUPPORTED_STAGES.contains(stage)) {
            return new Resolution(null, "付款阶段不在已支持配方内：" + input.paymentStage());
        }
        if (STAGE_DELIVERED_SETTLEMENT.equals(stage)) {
            // 已交付结算：不机械要求“合同+发票+物流”三件套；适用性来自角色与结算口径。
            return new Resolution(GOODS_SETTLED_V1, null);
        }
        // 预付款：付款在履约前、约定交期未到、且预付安排与背景相符（有明确合同与交期）。
        if (!input.payerMatchesContractBuyer()) {
            return new Resolution(null, "预付款要求付款主体与合同买方一致；代付需先提供明确授权记录");
        }
        if (trim(input.contractNumber()).length() < 3) {
            return new Resolution(null, "预付款需提供可定位的合同编号（预付条款核验的前提）");
        }
        LocalDate dueDate = parseDate(input.deliveryDueDate());
        if (dueDate == null) {
            return new Resolution(null, "预付款需声明合同约定交期（用于区分尚未到期与已逾期）");
        }
        return new Resolution(GOODS_PREPAY_V1, null);
    }

    /** 交期是否尚未到期（按注入 Clock 的评估日判断；解析失败按已过期处理，不猜测）。 */
    public boolean deliveryNotYetDue(Applicability input, LocalDate evaluateDate) {
        LocalDate due = parseDate(input == null ? null : input.deliveryDueDate());
        return due != null && !due.isBefore(evaluateDate);
    }

    /** 与已核定配方不一致的声明：提交时拒绝，不自动切换配方。 */
    public boolean matchesDeclared(String policyCode, Applicability input) {
        Resolution resolution = resolve(input);
        return resolution.applicable() && resolution.policyCode().equals(policyCode);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static LocalDate parseDate(String value) {
        String normalized = trim(value);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 供工作区展示：两种配方的六问题口径（已交付/预付需回答的重点）。 */
    public Map<String, String> questionFocus(String policyCode) {
        Map<String, String> focus = new LinkedHashMap<>();
        if (GOODS_PREPAY_V1.equals(policyCode)) {
            focus.put("Q1", "为什么需要预付，账期与采购模式是否相符");
            focus.put("Q2", "收入是订金、货款还是其他性质，是否确有依据");
            focus.put("Q3", "预付条款、收款授权、交期是否明确");
            focus.put("Q4", "本次预付占订单金额多少，余款/未来履约节点是什么");
            focus.put("Q5", "为什么需要同日预付，预付规模及风险如何被解释");
            focus.put("Q6", "未到期事项与已逾期事项是否区分；后续谁查什么");
        } else {
            focus.put("Q1", "企业角色、货物、结算规模与日常经营是否相符");
            focus.put("Q2", "付款人与买方是否一致；订单和收款是否对应");
            focus.put("Q3", "收款人、采购及交付是否对应");
            focus.put("Q4", "每笔命中交易怎样对应订单、金额和期间");
            focus.put("Q5", "为什么快速结算，收支差额属于什么");
            focus.put("Q6", "重要差异是否已解释、未知是否妨碍本次判断");
        }
        return focus;
    }
}
