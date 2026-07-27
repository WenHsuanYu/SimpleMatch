plugins {
  id("simplematch.spring-service")
}

val testSourceSet = the<JavaPluginExtension>().sourceSets.getByName("test")

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.kafka:spring-kafka")
  implementation(libs.grpc.netty.shaded)
  implementation(libs.quickfixj.core)
  implementation(libs.quickfixj.messages.fix44)

  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

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
