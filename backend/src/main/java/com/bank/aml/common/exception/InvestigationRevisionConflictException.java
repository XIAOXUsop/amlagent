package com.bank.aml.common.exception;

/**
 * 调查记录版本冲突：提交所基于的版本已不是当前版本（覆盖被他人更新，或判断依据假设已被改判）。
 * <p>
 * 统一映射为 HTTP 409 与稳定错误码 {@code INVESTIGATION_REVISION_CONFLICT}；
 * 客户端必须刷新调查事实后由用户明确重新确认，禁止自动重放旧结论。
 * <p>
 * 携带有界的冲突对象信息（类型/标识/当前版本），让页面可以区分 “覆盖被他人确认”（type=COVERAGE）与“假设被改判”（type=HYPOTHESIS），
 * 并刷新到正确版本；不携带调查文字等无界内容。
 */
public class InvestigationRevisionConflictException extends RuntimeException {

    /** 冲突对象类型：判断依据假设。 */
    public static final String TYPE_HYPOTHESIS = "HYPOTHESIS";

    /** 冲突对象类型：预警覆盖结论。 */
    public static final String TYPE_COVERAGE = "COVERAGE";

    private final String conflictType;

    private final Long conflictId;

    private final Integer currentVersion;

    public InvestigationRevisionConflictException(String conflictType, Long conflictId, Integer currentVersion,
            String message) {
        super(message);
        this.conflictType = conflictType;
        this.conflictId = conflictId;
        this.currentVersion = currentVersion;
    }

    public String getConflictType() {
        return conflictType;
    }

    public Long getConflictId() {
        return conflictId;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

}
