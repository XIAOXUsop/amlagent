package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PgFlywayInitializerTest {

    @Test
    void doesNotRunProductionMigrationsAgainstTestTable() {
        DataSource dataSource = mock(DataSource.class);
        RagProperties properties = new RagProperties();
        properties.getPg().setTable("legal_docs_tes");

        new PgFlywayInitializer(dataSource, properties).run(mock(ApplicationArguments.class));

        verifyNoInteractions(dataSource);
    }

}
