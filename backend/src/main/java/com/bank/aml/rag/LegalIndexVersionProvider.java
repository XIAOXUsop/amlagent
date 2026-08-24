package com.bank.aml.rag;

public interface LegalIndexVersionProvider {

    String activeVersion();

    /** 按检索目标解析应使用的索引版本身份。 */
    default String versionFor(RetrievalRequest request) {
        if (request == null) return activeVersion();
        if (request.target() == RetrievalTarget.SPECIFIC_VERSION) return request.specificVersion();
        if (request.target() == RetrievalTarget.CANDIDATE) return candidateVersion();
        return activeVersion();
    }

    /** CANDIDATE 目标对应的候选索引；无候选时返回空串。 */
    default String candidateVersion() {
        return activeVersion();
    }
}