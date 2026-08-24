package com.bank.aml.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 不依赖 Docker 的快速门禁：同一目录不能出现两个相同版本的 Flyway 迁移。 */
class FlywayResourceVersionTest {

    private static final Pattern VERSIONED = Pattern.compile("^V([^_]+)__.+\\.sql$");

    @Test
    void migrationVersionsAreUnique() throws Exception {
        Path migrationDirectory = Path.of("src", "main", "resources", "db", "migration");
        var duplicates = new HashSet<String>();
        var seen = new HashSet<String>();
        try (var files = Files.list(migrationDirectory)) {
            files.map(path -> path.getFileName().toString())
                    .map(VERSIONED::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(matcher -> matcher.group(1))
                    .filter(version -> !seen.add(version))
                    .forEach(duplicates::add);
        }

        assertThat(duplicates)
                .as("Flyway migration versions must be unique")
                .isEmpty();
    }
}
