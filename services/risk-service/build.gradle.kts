plugins {
  id("simplematch.spring-service")
  id("simplematch.flyway-service")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)

  testImplementation("com.h2database:h2")
  testImplementation(libs.flyway.core)
}

simpleMatchFlyway {
  serviceName.set("riskService")
  migrationLocations.set(listOf("filesystem:${project.projectDir}/src/main/resources/db/migration/risk-service"))
  baselineVersion.set("1")
  schemaName.set("risk_service")
}
