package com.bank.aml.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 长时间索引构建的租约心跳；失去所有权后构建者必须停止写入和发布。 */
@Component
public class RagBuildLeaseHeartbeat {
    private final LegalIndexVersionService versions;
    private final Duration interval;

    public RagBuildLeaseHeartbeat(LegalIndexVersionService versions,
                                  @Value("${aml.rag.ingestion.lease-heartbeat-seconds:60}") long seconds) {
        this.versions = versions;
        this.interval = Duration.ofSeconds(Math.max(5, seconds));
    }

    public Lease start(String version, String owner) {
        return new Lease(versions, version, owner, interval);
    }

    public static final class Lease implements AutoCloseable {
        private final LegalIndexVersionService versions;
        private final String version;
        private final String owner;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final ScheduledExecutorService scheduler;

        private Lease(LegalIndexVersionService versions, String version, String owner, Duration interval) {
            this.versions = versions;
            this.version = version;
            this.owner = owner;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "rag-index-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::heartbeat, interval.toSeconds(), interval.toSeconds(),
                    TimeUnit.SECONDS);
        }

        private void heartbeat() {
            try {
                if (!versions.renewBuildLease(version, owner)) valid.set(false);
            } catch (RuntimeException ignored) {
                valid.set(false);
            }
        }

        /** 在不可逆写入和发布前同步确认一次，消除仅依赖后台定时器的竞态窗口。 */
        public void assertAndRenew() {
            if (!valid.get() || !versions.renewBuildLease(version, owner)) {
                valid.set(false);
                throw new IllegalStateException("法规索引构建租约已失效，拒绝继续构建");
            }
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }
    }
}
