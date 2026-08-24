-- 法规向量库（PGVector）索引迁移。
-- V1 被保留为历史已有库的 baseline；真正需要执行的首个版本从 V2 开始，
-- 因而已有 legal_docs 但没有 flyway_schema_history 的数据库也不会跳过本脚本。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS legal_docs_text_trgm_idx
    ON legal_docs USING gin (text gin_trgm_ops);
CREATE INDEX IF NOT EXISTS legal_docs_fts_idx
    ON legal_docs USING gin (to_tsvector('simple', coalesce(text, '')));

CREATE INDEX IF NOT EXISTS legal_docs_corpus_version_idx
    ON legal_docs ((metadata::jsonb ->> 'corpusVersion'));
CREATE INDEX IF NOT EXISTS legal_docs_jurisdiction_idx
    ON legal_docs ((metadata::jsonb ->> 'jurisdiction'));
CREATE INDEX IF NOT EXISTS legal_docs_security_status_idx
    ON legal_docs ((metadata::jsonb ->> 'securityStatus'));
CREATE INDEX IF NOT EXISTS legal_docs_effective_from_idx
    ON legal_docs ((metadata::jsonb ->> 'effectiveFrom'));
CREATE INDEX IF NOT EXISTS legal_docs_effective_to_idx
    ON legal_docs ((metadata::jsonb ->> 'effectiveTo'));

-- 语料扩充到 >= 1 万 chunk 后再增加 HNSW，并在带过滤条件场景验证 iterative scan。
