package com.bank.aml.rag;

/**
 * 检索目标索引。内部参数，由服务端（评测门禁/管理方）指定，不对外部用户开放。
 * <ul>
 *   <li>{@link #ACTIVE}：当前生效索引（生产检索默认）；</li>
 *   <li>{@link #CANDIDATE}：最近一个候选/评测中的索引（发布门禁用）；</li>
 *   <li>{@link #SPECIFIC_VERSION}：显式版本身份（与 {@code RetrievalRequest.specificVersion} 配合）。</li>
 * </ul>
 */
public enum RetrievalTarget {
    ACTIVE,
    CANDIDATE,
    SPECIFIC_VERSION
}