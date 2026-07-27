plugins {
  java
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
}

val testSourceSet = the<JavaPluginExtension>().sourceSets.getByName("test")

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
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.kafka:spring-kafka")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation(libs.grpc.netty.shaded)
  implementation(libs.quickfixj.core)
  implementation(libs.quickfixj.messages.fix44)

  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.withType<Test>().configureEach {
  jvmArgs(
      "--enable-native-access=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow")
}

tasks.register<Test>("certificationTest") {
  group = "verification"
  description = "Runs QuickFIX/J certification-style simulator evidence tests."
  testClassesDirs = testSourceSet.output.classesDirs
  classpath = testSourceSet.runtimeClasspath
  useJUnitPlatform()
  filter {
    includeTestsMatching("com.simplematch.quickfixgateway.fix.QuickFixCertificationEvidenceTest")
  }
}
