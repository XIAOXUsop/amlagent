-- FR-01（v4 计划 §4.1）：核验事件绑定核验对象/事实键。
-- 同一材料可有多种核验动作、面向不同事实；取整条材料最后事件代表所有事实是错误覆盖。
-- subject_fact_key 为空表示材料级通用核验（兼容存量行，NULL=未绑定具体事实）；
-- 新写入必须显式声明核验对象（questionCode 或 claimId 事实键）。
ALTER TABLE evidence_verification_event
    ADD COLUMN subject_fact_key VARCHAR(96) NULL;

CREATE INDEX idx_verification_subject
    ON evidence_verification_event (artifact_version_id, subject_fact_key);
