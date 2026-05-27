plugins {
  `java-library`
}

dependencies {
  api("com.fasterxml.jackson.core:jackson-databind:2.19.2")
  api("org.springframework.boot:spring-boot:3.5.14")
  compileOnly("com.google.errorprone:error_prone_annotations:2.39.0")
  testImplementation("org.springframework.boot:spring-boot:3.5.14")
  testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.12.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}