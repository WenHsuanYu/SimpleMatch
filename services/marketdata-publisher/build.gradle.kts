plugins {
  id("simplematch.spring-service")
  id("simplematch.flyway-service")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation(libs.jackson.databind)

  testImplementation("com.h2database:h2")
  testImplementation(libs.flyway.core)
  testImplementation("org.springframework:spring-jdbc")
}

simpleMatchFlyway {
  serviceId.set("marketdata-publisher")
  schemaName.set("marketdata_publisher")
}

tasks.test {
  systemProperty("phase5.postgres.dsn", System.getProperty("phase5.postgres.dsn", ""))
}
