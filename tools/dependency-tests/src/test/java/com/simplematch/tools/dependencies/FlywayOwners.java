package com.simplematch.tools.dependencies;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Reads the actual service-owned Gradle Flyway configuration without connection credentials. */
final class FlywayOwners {
  private FlywayOwners() {}

  static Path root() {
    return Path.of(System.getProperty("dependencyRepoRoot"));
  }

  static Map<String, Map<String, String>> configurations() throws IOException {
    final String value = System.getProperty("dependencyFlywayOwners");
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Configured Flyway owners were not supplied by Gradle");
    }
    return new ObjectMapper().readValue(value, new TypeReference<>() {});
  }

  static Flyway connect(String owner, javax.sql.DataSource source) throws IOException {
    return Flyway.configure().configuration(configurations().get(owner)).dataSource(source).load();
  }
}
