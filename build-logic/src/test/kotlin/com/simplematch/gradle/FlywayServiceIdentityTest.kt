package com.simplematch.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FlywayServiceIdentityTest {
  @Test
  fun `preserves established root Flyway task prefixes`() {
    assertEquals("accountService", FlywayServiceIdentity.taskPrefix("account-service"))
    assertEquals("riskService", FlywayServiceIdentity.taskPrefix("risk-service"))
    assertEquals("persistence", FlywayServiceIdentity.taskPrefix("persistence"))
  }

  @Test
  fun `derives migration location from service identity`() {
    assertEquals(
        "filesystem:/workspace/risk/src/main/resources/db/migration/risk-service",
        FlywayServiceIdentity.defaultMigrationLocation(File("/workspace/risk"), "risk-service"))
  }

  @Test
  fun `maps legacy service names to kebab-case identifiers`() {
    assertEquals("account-service", FlywayServiceIdentity.legacyServiceNameToId("accountService"))
  }
}
