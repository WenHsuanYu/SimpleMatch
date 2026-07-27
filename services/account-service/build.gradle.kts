plugins {
  java
  id("org.springframework.boot")
  id("io.spring.dependency-management")
  id("simplematch.flyway-service")
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.1")
  }
}

dependencies {
  implementation(project(":shared-java:simplematch-config"))
  implementation(project(":shared-java:simplematch-contracts"))
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.cloud:spring-cloud-starter")
  implementation("io.grpc:grpc-netty-shaded:1.80.0")
  implementation("io.grpc:grpc-protobuf:1.80.0")
  implementation("io.grpc:grpc-stub:1.80.0")
  runtimeOnly("org.postgresql:postgresql")
  compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")

  testImplementation("com.h2database:h2")
  testImplementation("org.flywaydb:flyway-core:12.7.0")
  testImplementation("org.springframework:spring-jdbc")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

simpleMatchFlyway {
  serviceName.set("accountService")
  migrationLocations.set(listOf("filesystem:${project.projectDir}/src/main/resources/db/migration/account-service"))
  baselineVersion.set("1")
  schemaName.set("account_service")
}