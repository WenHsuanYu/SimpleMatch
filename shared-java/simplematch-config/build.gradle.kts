plugins {
  `java-library`
  id("simplematch.java-conventions")
  id("simplematch.java-quality")
}

dependencies {
  api("org.springframework.boot:spring-boot:${libs.versions.spring.boot.get()}")
  api("org.springframework.boot:spring-boot-autoconfigure:${libs.versions.spring.boot.get()}")
  implementation(libs.uuid.creator)
  compileOnly(libs.errorprone.annotations)
  testImplementation("org.springframework.boot:spring-boot:${libs.versions.spring.boot.get()}")
  testImplementation("org.springframework.boot:spring-boot-starter-test:${libs.versions.spring.boot.get()}")
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}
