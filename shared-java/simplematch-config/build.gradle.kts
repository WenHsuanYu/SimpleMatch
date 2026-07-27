plugins {
  `java-library`
}

dependencies {
  api(libs.jackson.databind)
  api("org.springframework.boot:spring-boot:${libs.versions.spring.boot.get()}")
  implementation(libs.uuid.creator)
  compileOnly(libs.errorprone.annotations)
  testImplementation("org.springframework.boot:spring-boot:${libs.versions.spring.boot.get()}")
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
