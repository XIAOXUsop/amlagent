package com.bank.aml.rag;

import com.bank.aml.config.LegalVectorTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalVectorTableTest {

    @Test
    void resolvesOnlyAllowlistedTableNames() {
        assertThat(LegalVectorTable.fromConfiguration("legal_docs")).isEqualTo(LegalVectorTable.PRODUCTION);
        assertThat(LegalVectorTable.fromConfiguration("legal_docs_tes")).isEqualTo(LegalVectorTable.TEST);
        assertThatThrownBy(() -> LegalVectorTable.fromConfiguration("legal_docs; drop table legal_docs"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("PGVector 表名不在允许列表中");
    }

}
