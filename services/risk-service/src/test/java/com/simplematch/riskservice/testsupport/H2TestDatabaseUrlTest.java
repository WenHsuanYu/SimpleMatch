package com.simplematch.riskservice.testsupport;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class H2TestDatabaseUrlTest {
  @Test
  void createsAConnectionToItsGeneratedDatabase() {
    assertThatCode(
            () -> {
              try (var ignored =
                  DriverManager.getConnection(H2TestDatabaseUrl.uniqueRiskServiceUrl())) {
                // Opening the connection verifies the database name and URL options together.
              }
            })
        .doesNotThrowAnyException();
  }
}
