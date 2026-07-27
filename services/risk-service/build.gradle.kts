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
  serviceId.set("risk-service")
  schemaName.set("risk_service")
}
