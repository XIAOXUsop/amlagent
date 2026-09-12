package com.bank.aml.explanation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceVerificationEventRepository extends JpaRepository<EvidenceVerificationEvent, Long> {

    List<EvidenceVerificationEvent> findByCaseIdOrderByEventTimeAsc(Long caseId);

    List<EvidenceVerificationEvent> findByArtifactVersionIdOrderByEventTimeAsc(Long artifactVersionId);

    /** FR-01：按材料版本 + 核验对象取核验链（事件序号升序 = 有效状态序）。 */
    List<EvidenceVerificationEvent> findByArtifactVersionIdAndSubjectFactKeyOrderByEventTimeAscIdAsc(
            Long artifactVersionId, String subjectFactKey);

    /** 材料级通用核验（subjectFactKey 为 NULL，存量兼容）。 */
    List<EvidenceVerificationEvent> findByArtifactVersionIdAndSubjectFactKeyIsNullOrderByEventTimeAscIdAsc(
            Long artifactVersionId);

}
