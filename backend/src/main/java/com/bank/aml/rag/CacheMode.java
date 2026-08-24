package com.bank.aml.rag;

/**
 * RAG 检索缓存模式（隔离评测流量，避免污染生产 Redis 缓存）。
 * <ul>
 *   <li>{@link #NORMAL}：读缓存并写缓存（生产检索默认）；</li>
 *   <li>{@link #BYPASS_READ_WRITE}：不读也不写（离线评测、候选门禁）；</li>
 *   <li>{@link #READ_ONLY}：只读缓存、不写（分析/观测流量）。</li>
 * </ul>
 */
public enum CacheMode {
    NORMAL,
    BYPASS_READ_WRITE,
    READ_ONLY
}