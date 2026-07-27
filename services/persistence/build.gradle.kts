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
  implementation("org.springframework.boot:spring-boot-starter-validation")
  runtimeOnly("org.postgresql:postgresql")
  compileOnly(libs.jakarta.annotation.api)

  testImplementation("com.h2database:h2")
  testImplementation(libs.flyway.core)
  testImplementation("org.springframework:spring-jdbc")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

simpleMatchFlyway {
  serviceName.set("persistence")
  migrationLocations.set(listOf("filesystem:${project.projectDir}/src/main/resources/db/migration/persistence"))
  baselineVersion.set("1")
  schemaName.set("persistence")
}
