plugins {
  java
  id("org.springframework.boot")
  id("io.spring.dependency-management")
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.1")
  }
}

dependencies {
  implementation(project(":java-libs:simplematch-config"))
  implementation(project(":java-libs:simplematch-contracts"))
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.cloud:spring-cloud-starter")
  implementation("io.grpc:grpc-netty-shaded:1.80.0")
  implementation("io.grpc:grpc-protobuf:1.80.0")
  implementation("io.grpc:grpc-stub:1.80.0")
  compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
}