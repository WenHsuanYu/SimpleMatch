plugins {
    id("simplematch.spring-service")
    id("simplematch.flyway-service")
    id("simplematch.contract-test-fixtures")
}

val testSourceSet = the<JavaPluginExtension>().sourceSets.getByName("test")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation(libs.grpc.netty.shaded)
    implementation(libs.quickfixj.core)
    implementation(libs.quickfixj.messages.fix44)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.h2)
    testImplementation(libs.flyway.core)
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

simpleMatchFlyway {
    serviceId.set("quickfix-gateway")
    schemaName.set("quickfix_gateway")
}

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("com.simplematch.quickfixgateway.fix.QuickFixCertificationEvidenceTest")
        excludeTestsMatching("com.simplematch.quickfixgateway.fix.QuickFixLiveCertificationTest")
    }
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

tasks.register<Test>("liveCertificationTest") {
    group = "verification"
    description = "Runs the opt-in external QuickFIX gateway live certification."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.simplematch.quickfixgateway.fix.QuickFixLiveCertificationTest")
    }
}
