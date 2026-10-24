package com.simplematch.queryservice.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryLifecycleFixtureConfigurationTest {
  @TempDir Path directory;

  @Test
  void publisherConfigurationIsAcceptedByTheActualKafkaProducer() throws Exception {
    final Path identities = directory.resolve("fixture-identities.json");
    Files.writeString(identities, "{}");
    assertDoesNotThrow(() -> QueryLifecycleFixtureMain.main(
        new String[] {identities.toString(), "check-configuration"}));
  }
}
