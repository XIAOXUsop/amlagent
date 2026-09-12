package com.bank.aml.assistant.snapshot;

import com.bank.aml.assistant.domain.CustomerAssistantSnapshot;
import com.bank.aml.assistant.persistence.entity.AssistantSnapshotEntity;
import com.bank.aml.assistant.persistence.repository.AssistantSnapshotRepository;
import com.bank.aml.common.time.BusinessZone;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantSnapshotArchiveService {

    private final AssistantSnapshotRepository repository;

    private final ObjectMapper objectMapper;

    public AssistantSnapshotArchiveService(AssistantSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssistantSnapshotEntity archive(CustomerAssistantSnapshot snapshot) {
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            return repository.save(AssistantSnapshotEntity.create(snapshot.snapshotId(), snapshot.runId(), payload,
                    snapshot.sourceDigest(), snapshot.sourceSystem(), snapshot.sourceVersion(),
                    snapshot.knowledgeIndexVersion(), LocalDateTime.ofInstant(snapshot.asOfTime(), BusinessZone.ZONE)));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("AI 小助快照序列化失败", e);
        }
    }

}
