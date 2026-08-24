package com.bank.aml.rag.ingestion;

import com.bank.aml.datasource.entity.RagDocumentQuarantineEntity;
import com.bank.aml.datasource.repository.RagDocumentQuarantineRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class LegalCorpusSecurityGate {
    private final LegalCorpusSecurityScanner scanner;
    private final RagDocumentQuarantineRepository quarantine;

    public LegalCorpusSecurityGate(LegalCorpusSecurityScanner scanner,
                                   RagDocumentQuarantineRepository quarantine) {
        this.scanner = scanner;
        this.quarantine = quarantine;
    }

    public void validate(List<Path> files) {
        // 本方法不能开启一个覆盖整批扫描的事务：发现危险文件后抛出的异常会回滚事务，
        // 从而把刚写入的隔离审计记录一起抹掉。Repository 自身的小事务会在抛错前提交。
        List<String> unsafeCodes = new ArrayList<>();
        for (Path file : files) {
            LegalCorpusSecurityScanner.ScanResult result = scanner.scan(file);
            if (result.safe()) continue;
            unsafeCodes.addAll(result.reasonCodes());
            if (!quarantine.existsBySourceFileAndFileHash(result.sourceFile(), result.fileHash())) {
                RagDocumentQuarantineEntity entity = new RagDocumentQuarantineEntity();
                entity.setSourceFile(result.sourceFile());
                entity.setFileHash(result.fileHash());
                entity.setReasonCodes(String.join(",", result.reasonCodes()));
                entity.setDetectedAt(LocalDateTime.now());
                quarantine.save(entity);
            }
        }
        if (!unsafeCodes.isEmpty()) {
            throw new UnsafeLegalCorpusException(List.copyOf(new java.util.LinkedHashSet<>(unsafeCodes)));
        }
    }

    public static class UnsafeLegalCorpusException extends RuntimeException {
        private final List<String> reasonCodes;
        public UnsafeLegalCorpusException(List<String> reasonCodes) {
            super("法规语料未通过安全扫描: " + String.join(",", reasonCodes));
            this.reasonCodes = List.copyOf(reasonCodes);
        }
        public List<String> reasonCodes() { return reasonCodes; }
    }
}
