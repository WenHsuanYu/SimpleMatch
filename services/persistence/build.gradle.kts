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
  serviceId.set("persistence")
  schemaName.set("persistence")
}
