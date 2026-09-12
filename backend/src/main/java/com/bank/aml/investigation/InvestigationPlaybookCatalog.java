package com.bank.aml.investigation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** P0 调查剧本闭集；用于把监测规则转成可执行的问题和证据要求。 */
@Component
public class InvestigationPlaybookCatalog {

    private final Map<String, Playbook> playbooks = new LinkedHashMap<>();

    public InvestigationPlaybookCatalog() {
        register(new Playbook("STRUCTURING", "拆分交易规避监测", "STRUCTURING_THRESHOLD_AVOIDANCE", "疑似通过拆分交易规避监测阈值",
                "短期内多笔接近阈值的交易是否具有同一目的、对手或资金来源？",
                List.of(InvestigationEvidenceType.TRANSACTION, InvestigationEvidenceType.CUSTOMER_PROFILE),
                "核实交易聚合金额、时间间隔、共同对手及业务凭证。"));
        register(new Playbook("RAPID_MOVEMENT", "资金快进快出", "RAPID_PASS_THROUGH_FUNDS", "账户可能被用于资金过渡",
                "入账资金是否在短期内以相近金额转出，且缺少与客户经营相符的留存和用途？",
                List.of(InvestigationEvidenceType.TRANSACTION, InvestigationEvidenceType.DOCUMENT),
                "核实入出账关联、停留时长、最终去向及基础交易。"));
        register(new Playbook("CROSS_BORDER_ANOMALY", "异常跨境交易", "UNEXPLAINED_CROSS_BORDER_ACTIVITY", "跨境活动与客户业务背景可能不一致",
                "跨境国家、币种、交易对手和交易目的是否与客户申报经营活动一致？", List.of(InvestigationEvidenceType.TRANSACTION,
                        InvestigationEvidenceType.CUSTOMER_PROFILE, InvestigationEvidenceType.DOCUMENT),
                "关注高风险地区、首次出现的国家或对手、夜间集中及贸易单据一致性。"));
        register(new Playbook("PROFILE_MISMATCH", "交易与客户画像不匹配", "TRANSACTION_PROFILE_MISMATCH", "交易规模或模式可能偏离客户画像",
                "交易金额、频率、渠道和对手是否能够由客户职业、收入或经营规模合理解释？",
                List.of(InvestigationEvidenceType.TRANSACTION, InvestigationEvidenceType.CUSTOMER_PROFILE),
                "对比历史基线、收入经营规模、账户用途与近期重大变化。"));
        register(new Playbook("COMPLEX_OWNERSHIP", "复杂受益所有权", "OBSCURED_BENEFICIAL_OWNER", "复杂控制结构可能掩盖实际受益所有人",
                "股权、协议控制和关联关系能否穿透至自然人，备案信息是否与核验结果一致？",
                List.of(InvestigationEvidenceType.BENEFICIAL_OWNERSHIP, InvestigationEvidenceType.EXTERNAL_DATA),
                "核实逐层持股、实际控制、代持迹象及受益所有人信息差异。"));
        register(new Playbook("SANCTIONS_WATCHLIST", "名单身份核验", "TRUE_WATCHLIST_MATCH", "客户或关联方可能真实命中制裁/关注名单",
                "姓名、证件、出生日期、国籍和关联关系是否足以确认或排除名单候选？",
                List.of(InvestigationEvidenceType.SANCTIONS_SCREENING, InvestigationEvidenceType.CUSTOMER_PROFILE),
                "避免仅凭姓名确认，必须核对多项身份要素和名单版本。"));
    }

    private void register(Playbook playbook) {
        playbooks.put(playbook.code(), playbook);
    }

    public List<Playbook> all() {
        return List.copyOf(playbooks.values());
    }

    public Playbook require(String code) {
        Playbook result = code == null ? null : playbooks.get(code.trim().toUpperCase(Locale.ROOT));
        if (result == null)
            throw new IllegalArgumentException("不支持的调查场景：" + code);
        return result;
    }

    public Playbook resolve(String explicitCode, String ruleCode, String reason) {
        if (explicitCode != null && !explicitCode.isBlank())
            return require(explicitCode);
        String text = ((ruleCode == null ? "" : ruleCode) + " " + (reason == null ? "" : reason))
            .toUpperCase(Locale.ROOT);
        if (containsAny(text, "拆分", "STRUCTUR", "阈值"))
            return require("STRUCTURING");
        if (containsAny(text, "快进快出", "过渡", "RAPID", "PASS_THROUGH"))
            return require("RAPID_MOVEMENT");
        if (containsAny(text, "跨境", "境外", "CROSS_BORDER", "夜间"))
            return require("CROSS_BORDER_ANOMALY");
        if (containsAny(text, "股权", "受益所有", "UBO", "控制人"))
            return require("COMPLEX_OWNERSHIP");
        if (containsAny(text, "制裁", "名单", "SANCTION", "WATCHLIST"))
            return require("SANCTIONS_WATCHLIST");
        return require("PROFILE_MISMATCH");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values)
            if (text.contains(value))
                return true;
        return false;
    }

    public record Playbook(String code, String name, String defaultHypothesisCode, String defaultHypothesisTitle,
            String investigationQuestion, List<InvestigationEvidenceType> requiredEvidenceTypes,
            String escalationFocus) {
    }

}
