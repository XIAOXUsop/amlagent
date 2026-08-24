package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import com.bank.aml.datasource.repository.RagIndexManifestRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 清理失败/退役索引；活动版本与最近可回滚版本永远受保护。 */
@Service
public class LegalIndexMaintenanceService {
    private final JdbcTemplate pgJdbc;
    private final RagIndexManifestRepository manifests;
    private final LegalIndexVersionService versions;
    private final String table;
    private final int retainedRetiredVersions;

    public LegalIndexMaintenanceService(@Qualifier("pgDataSource") DataSource dataSource,
                                        RagProperties properties,
                                        RagIndexManifestRepository manifests,
                                        LegalIndexVersionService versions,
                                        @Value("${aml.rag.retained-retired-versions:2}") int retainedRetiredVersions) {
        if (!properties.getPg().getTable().matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法 PGVector 表名");
        }
        this.pgJdbc = new JdbcTemplate(dataSource);
        this.table = properties.getPg().getTable();
        this.manifests = manifests;
        this.versions = versions;
        this.retainedRetiredVersions = Math.max(1, retainedRetiredVersions);
    }

    public CleanupResult cleanup() {
        Set<String> protectedVersions = new LinkedHashSet<>();
        protectedVersions.add(versions.activeVersion());
        protectedVersions.add(versions.previousVersion());
        manifests.findByStatusOrderByUpdatedAtDesc("RETIRED").stream()
                .limit(retainedRetiredVersions)
                .map(com.bank.aml.datasource.entity.RagIndexManifestEntity::getIndexVersion)
                .forEach(protectedVersions::add);
        protectedVersions.remove("");

        List<String> purgeable = manifests.findAllByOrderByCreatedAtDesc().stream()
                .filter(m -> Set.of("FAILED", "RETIRED", "PURGING").contains(m.getStatus()))
                .map(com.bank.aml.datasource.entity.RagIndexManifestEntity::getIndexVersion)
                .filter(version -> !protectedVersions.contains(version))
                .toList();
        int vectors = 0;
        List<String> deleted = new java.util.ArrayList<>();
        for (String version : purgeable) {
            // MySQL 与 PGVector 之间不伪装分布式原子性：先提交可重试的 PURGING 状态，
            // PG 删除失败时下次清理继续；并与 rollback 串行化，禁止删除刚激活的版本。
            if (!versions.markPurgingIfSafe(version, protectedVersions)) continue;
            vectors += pgJdbc.update("DELETE FROM " + table
                    + " WHERE metadata::jsonb ->> 'corpusVersion' = ?", version);
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
