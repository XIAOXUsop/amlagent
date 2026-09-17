package com.bank.aml.explanation;

/**
 * 集团代付配方的 C1~C6 事实字典：每一问「问什么、去哪里取、下一步做什么、有什么替代路径」。
 *
 * <p>
 * 它是**领域词汇表**，不是逻辑：四个方法都是同样的 switch，改一处文案不该碰服务的任何状态。 原先它们散在 2500 行的服务里当私有静态方法，看起来像业务规则，
 * 实际只是把这段中文文案放在离调用点更近的地方。
 *
 * <p>
 * 纯查表、零依赖、无状态——这也是它适合单独存在的原因。
 */
final class ExplanationFactCatalog {

    private ExplanationFactCatalog() {
    }

    /** 这一问在问什么 */
    static String question(String code) {
        return switch (code) {
            case "C1" -> "付款账户所属主体与合同买方是否同一法定主体（主体消歧）";
            case "C2" -> "买方因哪项交付对卖方负有多少货款义务";
            case "C3" -> "谁授权谁、向谁、付哪笔、多少、何时有效";
            default -> "这两笔钱是否在授权范围内履行了授权所指义务";
        };
    }

    /** 建议去哪里取证 */
    static String suggestedSource(String code) {
        return switch (code) {
            case "C1" -> "核心系统账户归属 + KYC 主体标识";
            case "C2" -> "订单/履约/验收记录";
            case "C3" -> "可定位的授权版本及独立来源确认";
            default -> "权威流水与授权/订单的逐笔分配";
        };
    }

    /** 建议的下一步动作 */
    static String suggestedAction(String code) {
        return switch (code) {
            case "C1" -> "查询付款账户所属主体，核对买方历史名称后再判断是否代付";
            case "C2" -> "核对指定订单与交付记录，不泛要全部财务资料";
            case "C3" -> "对授权的签发、范围、撤销状态做一次独立确认";
            default -> "核对无法解释的具体交易与超限差额";
        };
    }

    /** 替代路径（拿不到首选材料时怎么办） */
    static String alternative(String code) {
        return switch (code) {
            case "C1" -> "同名/简称/历史名称先做主体消歧；确认同一主体回到普通货款流程";
            case "C2" -> "往来核对记录可作为替代（单独一张发票不能覆盖全部结论）";
            case "C3" -> "预先核实渠道取得的买方确认（联系电话不能仅来自本次可疑材料）";
            default -> "买方或收款方入账用途核对";
        };
    }

}
