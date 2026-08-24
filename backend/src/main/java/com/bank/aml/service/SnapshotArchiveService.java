package com.bank.aml.service;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.security.SensitivePayloadCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在模型调用前归档并在审计/回放时校验加载完整快照。 */
@Service
public class SnapshotArchiveService {
    private final InvestigationSnapshotRepository repository;
    private final CustomerDataPort dataSource;
    private final ObjectMapper objectMapper;

    public SnapshotArchiveService(InvestigationSnapshotRepository repository, CustomerDataPort dataSource,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void archive(InvestigationSnapshot snapshot) {
        if (repository.existsById(snapshot.snapshotId())) return;
        InvestigationSnapshotEntity entity = new InvestigationSnapshotEntity();
        entity.setSnapshotId(snapshot.snapshotId());
        entity.setCaseId(snapshot.caseId());
        entity.setExecutionVersion(snapshot.executionVersion());
        entity.setAsOfTime(snapshot.asOfTime());
        entity.setSourceSystem(dataSource.sourceSystem());
        entity.setSourceVersion(dataSource.sourceVersion());
        entity.setLegalIndexVersion(snapshot.legalIndexVersion());
        entity.setSourceDigest(snapshot.sourceDigest());
        entity.setPayloadCiphertext(SensitivePayloadCipher.encrypt(write(snapshot)));
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public InvestigationSnapshot loadAndVerify(String snapshotId) {
        InvestigationSnapshotEntity entity = repository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("尽调快照不存在：" + snapshotId));
        InvestigationSnapshot snapshot = read(SensitivePayloadCipher.decrypt(entity.getPayloadCiphertext()));
        if (!entity.getSnapshotId().equals(snapshot.snapshotId())
                || !entity.getCaseId().equals(snapshot.caseId())
                || entity.getExecutionVersion() != snapshot.executionVersion()
                || !entity.getSourceDigest().equals(snapshot.sourceDigest())
                || !entity.getLegalIndexVersion().equals(snapshot.legalIndexVersion())) {
            throw new IllegalStateException("尽调快照归档元数据校验失败：" + snapshotId);
        }
        return snapshot;
    }

    private String write(InvestigationSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("尽调快照序列化失败", e);
        }
    }

    private InvestigationSnapshot read(String json) {
        try {
            return objectMapper.readValue(json, InvestigationSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("尽调快照反序列化失败", e);
        }
    }
}
