plugins {
  java
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  id("simplematch.flyway-service")
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.spring.cloud.get()}")
  }
}

dependencies {
  implementation(project(":shared-java:simplematch-config"))
  implementation(project(":shared-java:simplematch-contracts"))
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)
  runtimeOnly("org.postgresql:postgresql")
  compileOnly(libs.jakarta.annotation.api)

  testImplementation("com.h2database:h2")
  testImplementation(libs.flyway.core)
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

simpleMatchFlyway {
  serviceName.set("riskService")
  migrationLocations.set(listOf("filesystem:${project.projectDir}/src/main/resources/db/migration/risk-service"))
  baselineVersion.set("1")
  schemaName.set("risk_service")
}
