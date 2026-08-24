package com.bank.aml.sanction;

import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.SanctionRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地确定性制裁名单匹配器。
 * <p>数据源的 LIKE 查询只负责召回候选，本类负责“证件号、姓名别名、主体类型”核验，
 * 避免把包含客户姓名的公司条目直接当成自然人命中。实现不把客户身份数据发送到第三方服务。
 */
@Component
public class SanctionMatchScorer {

    private static final Pattern PAREN_ALIAS = Pattern.compile("[（(]([^）)]+)[）)]");
    private static final Pattern COMPANY_MARKERS = Pattern.compile(
            "公司|有限公司|集团|银行|基金|TRADING|COMPANY|\\bCO\\b|\\bLTD\\b|\\bINC\\b|\\bCORP\\b",
            Pattern.CASE_INSENSITIVE);

    public SanctionCandidateMatch score(CustomerProfile customer, SanctionRecord candidate) {
        String customerIdentity = normalizeIdentity(customer.idCard());
        String candidateIdentity = normalizeIdentity(candidate.idCard());
        boolean identityExact = !customerIdentity.isEmpty() && customerIdentity.equals(candidateIdentity);
        boolean identityConflict = !customerIdentity.isEmpty() && !candidateIdentity.isEmpty() && !identityExact;

        String customerName = normalizeName(customer.name());
        Set<String> candidateNames = nameVariants(candidate.name());
        boolean nameExact = !customerName.isEmpty() && candidateNames.contains(customerName);
        double similarity = candidateNames.stream()
                .mapToDouble(name -> similarity(customerName, name))
                .max().orElse(0);
        boolean nameContained = !customerName.isEmpty() && candidateNames.stream()
                .anyMatch(name -> name.contains(customerName) || customerName.contains(name));
        boolean typeConflict = looksLikeCompany(candidate.name()) && !looksLikeCompany(customer.name());

        List<String> reasons = new ArrayList<>();
        int score;
        SanctionMatchDecision decision;
        String explanation;

        if (identityExact) {
            score = 100;
            decision = SanctionMatchDecision.CONFIRMED;
            reasons.add("IDENTITY_EXACT");
            if (nameExact) reasons.add("NAME_ALIAS_EXACT");
            explanation = "证件号码一致，属于高置信名单命中";
        } else if (identityConflict) {
            score = nameExact ? 30 : 10;
            decision = SanctionMatchDecision.DISMISSED;
            reasons.add("IDENTITY_CONFLICT");
            if (nameExact) reasons.add("NAME_ALIAS_EXACT");
            explanation = "姓名可能相同，但证件号码冲突，已排除自动命中";
        } else if (typeConflict) {
            score = nameContained || nameExact ? 35 : 0;
            decision = SanctionMatchDecision.DISMISSED;
            reasons.add("SUBJECT_TYPE_CONFLICT");
            if (nameContained) reasons.add("NAME_CONTAINED_ONLY");
            explanation = "名单主体为企业而客户姓名表现为自然人，仅字符串包含不足以确认命中";
        } else if (nameExact) {
            score = 88;
            decision = SanctionMatchDecision.REVIEW_REQUIRED;
            reasons.add("NAME_ALIAS_EXACT");
            reasons.add("IDENTITY_MISSING");
            explanation = "姓名或别名完全一致，但名单缺少可交叉核验的证件号码";
        } else if (similarity >= 0.88) {
            score = 72;
            decision = SanctionMatchDecision.REVIEW_REQUIRED;
            reasons.add("NAME_HIGH_SIMILARITY");
            reasons.add("IDENTITY_MISSING");
            explanation = "姓名高度相似，需人工结合生日、国籍等更多身份要素核验";
        } else if (nameContained) {
            score = 55;
            decision = SanctionMatchDecision.REVIEW_REQUIRED;
            reasons.add("NAME_CONTAINED_ONLY");
            reasons.add("IDENTITY_MISSING");
            explanation = "仅存在姓名包含关系，当前证据不足以形成确定命中";
        } else {
            score = 0;
            decision = SanctionMatchDecision.DISMISSED;
            reasons.add("NO_IDENTITY_MATCH");
            explanation = "未发现足以支持名单命中的身份要素";
        }

        return new SanctionCandidateMatch(fingerprint(candidate), candidate.name(), maskIdentity(candidate.idCard()),
                candidate.listType(), candidate.detail(), candidate.severity(), score, decision, decision,
                List.copyOf(reasons), explanation, null, 0, null, null, null);
    }

    private Set<String> nameVariants(String value) {
        Set<String> variants = new LinkedHashSet<>();
        String whole = normalizeName(value);
        if (!whole.isEmpty()) variants.add(whole);
        if (value != null) {
            Matcher matcher = PAREN_ALIAS.matcher(value);
            while (matcher.find()) {
                String alias = normalizeName(matcher.group(1));
                if (!alias.isEmpty()) variants.add(alias);
            }
            for (String part : value.split("[/,，;；|]")) {
                String alias = normalizeName(part);
                if (!alias.isEmpty()) variants.add(alias);
            }
        }
        return variants;
    }

    private String normalizeName(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String normalizeIdentity(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private boolean looksLikeCompany(String value) {
        return value != null && COMPANY_MARKERS.matcher(value).find();
    }

    private double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int maxLength = Math.max(left.length(), right.length());
        return 1.0 - (double) levenshtein(left, right) / maxLength;
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private String maskIdentity(String value) {
        String normalized = normalizeIdentity(value);
        if (normalized.isEmpty()) return "-";
        if (normalized.length() <= 4) return "****";
        return normalized.substring(0, 2) + "*".repeat(normalized.length() - 4)
                + normalized.substring(normalized.length() - 2);
    }

    private String fingerprint(SanctionRecord candidate) {
        String material = normalizeName(candidate.name()) + "|" + normalizeIdentity(candidate.idCard())
                + "|" + normalizeName(candidate.listType()) + "|" + candidate.severity()
                + "|" + normalizeName(candidate.detail());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }
}
