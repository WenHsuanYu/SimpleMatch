plugins {
  id("simplematch.spring-service")
  id("simplematch.flyway-service")
}

dependencies {
  testImplementation("com.h2database:h2")
  testImplementation(libs.flyway.core)
  testImplementation("org.springframework:spring-jdbc")
}

simpleMatchFlyway {
  serviceName.set("persistence")
  migrationLocations.set(listOf("filesystem:${project.projectDir}/src/main/resources/db/migration/persistence"))
  baselineVersion.set("1")
  schemaName.set("persistence")
}
