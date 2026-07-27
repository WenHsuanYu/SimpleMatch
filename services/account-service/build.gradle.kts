plugins {
  id("simplematch.spring-service")
  id("simplematch.flyway-service")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.cloud:spring-cloud-starter")
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)

  testImplementation("com.h2database:h2")
  testImplementation(libs.flyway.core)
  testImplementation("org.springframework:spring-jdbc")
}

simpleMatchFlyway {
  serviceName.set("accountService")
  migrationLocations.set(listOf("filesystem:${project.projectDir}/src/main/resources/db/migration/account-service"))
  baselineVersion.set("1")
  schemaName.set("account_service")
}
