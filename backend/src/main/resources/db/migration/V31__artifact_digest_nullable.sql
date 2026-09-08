-- A6-06（RC-02）：来源不可用/不存在（NOT_FOUND / UNAVAILABLE / FORBIDDEN）的抓取尝试
-- 允许没有内容摘要；RESOLVED 必须具有服务器计算的摘要。
-- 不修改已应用的 V27 校验和；本迁移只放宽列约束并补一致性检查。
-- 约束说明：MySQL CHECK（8.0.16+ 生效）；RESOLVED 行必须有 64 位十六进制摘要。

ALTER TABLE evidence_artifact_version
    MODIFY COLUMN content_sha256 CHAR(64) NULL;

ALTER TABLE evidence_artifact_version
    ADD CONSTRAINT chk_artifact_resolved_has_digest
    CHECK (availability <> 'RESOLVED'
           OR (content_sha256 IS NOT NULL
               AND CHAR_LENGTH(content_sha256) = 64
               AND content_sha256 REGEXP '^[a-f0-9]{64}$'));
