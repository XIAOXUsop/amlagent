package com.bank.aml.rag;

import com.bank.aml.config.LegalVectorTable;
import com.bank.aml.config.RagProperties;
import com.bank.aml.datasource.entity.RagIndexManifestEntity;
import com.bank.aml.datasource.repository.RagIndexManifestRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 清理失败/退役索引；活动版本与最近可回滚版本永远受保护。 */
@Service
public class LegalIndexMaintenanceService {

    private final JdbcTemplate pgJdbc;

    private final RagIndexManifestRepository manifests;

    private final LegalIndexVersionService versions;

    private final LegalVectorTable table;

    private final int retainedRetiredVersions;

    public LegalIndexMaintenanceService(@Qualifier("pgDataSource") DataSource dataSource, RagProperties properties,
            RagIndexManifestRepository manifests, LegalIndexVersionService versions) {
        this.pgJdbc = new JdbcTemplate(dataSource);
        this.table = LegalVectorTable.fromConfiguration(properties.getPg().getTable());
        this.manifests = manifests;
        this.versions = versions;
        this.retainedRetiredVersions = properties.getRetainedRetiredVersions();
    }

    public CleanupResult cleanup() {
        Set<String> protectedVersions = new LinkedHashSet<>();
        protectedVersions.add(versions.activeVersion());
        protectedVersions.add(versions.previousVersion());
        manifests.findByStatusOrderByUpdatedAtDesc("RETIRED")
            .stream()
            .limit(retainedRetiredVersions)
            .map(RagIndexManifestEntity::getIndexVersion)
            .forEach(protectedVersions::add);
        protectedVersions.remove("");

        List<String> purgeable = manifests.findAllByOrderByCreatedAtDesc()
            .stream()
            .filter(m -> Set.of("FAILED", "RETIRED", "PURGING").contains(m.getStatus()))
            .map(RagIndexManifestEntity::getIndexVersion)
            .filter(version -> !protectedVersions.contains(version))
            .toList();
        int vectors = 0;
        List<String> deleted = new ArrayList<>();
        for (String version : purgeable) {
            // MySQL 与 PGVector 之间不伪装分布式原子性：先提交可重试的 PURGING 状态，
            // PG 删除失败时下次清理继续；并与 rollback 串行化，禁止删除刚激活的版本。
            if (!versions.markPurgingIfSafe(version, protectedVersions))
                continue;
            vectors += pgJdbc.update(table.deleteByVersionSql(), version);
            manifests.deleteById(version);
            deleted.add(version);
        }
        return new CleanupResult(deleted, vectors);
    }

    public record CleanupResult(List<String> deletedVersions, int deletedVectors) {
        public CleanupResult {
            deletedVersions = List.copyOf(deletedVersions);
        }
    }

}
