package com.bank.aml.service;

import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.security.SensitivePayloadCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在模型调用前归档并在审计/回放时校验加载完整快照。
 * <p>
 * 幂等归档不是静默跳过：相同执行版本已有归档时，必须校验两次构建内容一致（业务事实摘要、 预警摘要与法规索引版本）；不一致说明同一次执行读到了不同的数据边界，必须显式失败，
 * 不能让模型使用新构建内容而归档服务保留另一份内容。
 */
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
        var existing = repository.findById(snapshot.snapshotId());
        if (existing.isPresent()) {
            verifySameExecution(snapshot, existing.get());
            return;
        }
        InvestigationSnapshotEntity entity = new InvestigationSnapshotEntity();
        entity.setSnapshotId(snapshot.snapshotId());
        entity.setCaseId(snapshot.caseId());
        entity.setExecutionVersion(snapshot.executionVersion());
        entity.setAsOfTime(snapshot.asOfTime());
        entity.setSourceSystem(dataSource.sourceSystem());
        entity.setSourceVersion(dataSource.sourceVersion());
        entity.setLegalIndexVersion(snapshot.legalIndexVersion());
        entity.setSourceDigest(snapshot.sourceDigest());
        entity.setAlertsDigest(snapshot.alertsDigest());
        entity.setPayloadCiphertext(SensitivePayloadCipher.encrypt(write(snapshot)));
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public InvestigationSnapshot loadAndVerify(String snapshotId) {
        InvestigationSnapshotEntity entity = repository.findById(snapshotId)
            .orElseThrow(() -> new IllegalArgumentException("尽调快照不存在：" + snapshotId));
        InvestigationSnapshot snapshot = read(SensitivePayloadCipher.decrypt(entity.getPayloadCiphertext()));
        if (!entity.getSnapshotId().equals(snapshot.snapshotId()) || !entity.getCaseId().equals(snapshot.caseId())
                || entity.getExecutionVersion() != snapshot.executionVersion()
                || !entity.getSourceDigest().equals(snapshot.sourceDigest())
                || !entity.getLegalIndexVersion().equals(snapshot.legalIndexVersion())
                || !Objects.equals(entity.getAlertsDigest(), snapshot.alertsDigest())) {
            throw new IllegalStateException("尽调快照归档元数据校验失败：" + snapshotId);
        }
        return snapshot;
    }

    /** 相同执行版本的归档冲突检测：摘要不一致即拒绝，不允许“模型用新内容、归档留旧内容”。 */
    private void verifySameExecution(InvestigationSnapshot snapshot, InvestigationSnapshotEntity existing) {
        boolean same = existing.getCaseId().equals(snapshot.caseId())
                && existing.getExecutionVersion() == snapshot.executionVersion()
                && existing.getSourceDigest().equals(snapshot.sourceDigest())
                && existing.getLegalIndexVersion().equals(snapshot.legalIndexVersion())
                && Objects.equals(existing.getAlertsDigest(), snapshot.alertsDigest());
        if (!same) {
            throw new IllegalStateException(
                    "尽调快照归档冲突：执行版本 " + snapshot.snapshotId() + " 已存在内容不一致的归档（业务事实/预警输入/法规索引发生变化），请人工排查");
        }
    }

    private String write(InvestigationSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("尽调快照序列化失败", e);
        }
    }

    private InvestigationSnapshot read(String json) {
        try {
            return objectMapper.readValue(json, InvestigationSnapshot.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("尽调快照反序列化失败", e);
        }
    }

}
