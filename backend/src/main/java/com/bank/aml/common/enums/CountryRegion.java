package com.bank.aml.common.enums;

/**
 * 交易涉及国家/地区（统一编码，替代自由字符串）。
 */
public enum CountryRegion {
    CHINA("中国大陆"),
    HK("中国香港"),
    IRAN("伊朗"),
    UAE("阿联酋"),
    US("美国"),
    OTHER("其他");

    private final String label;

    CountryRegion(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否跨境（非中国大陆） */
    public boolean isCrossBorder() {
        return this != CHINA;
    }
}
