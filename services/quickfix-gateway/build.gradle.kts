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
        excludeTestsMatching(
            "com.simplematch.quickfixgateway.fix.QuickFixRetainedSessionLiveCertificationTest"
        )
        excludeTestsMatching(
            "com.simplematch.quickfixgateway.fix.QuickFixPreparedSubmissionLiveCertificationTest"
        )
    }
}

fun Test.configureLiveCertification(testClassName: String) {
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    doNotTrackState(
        "Runs against a live FIX endpoint and writes evidence outside Gradle-managed outputs."
    )
    filter {
        includeTestsMatching(testClassName)
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
    configureLiveCertification("com.simplematch.quickfixgateway.fix.QuickFixLiveCertificationTest")
}

tasks.register<Test>("retainedSessionCertificationTest") {
    group = "verification"
    description = "Runs the opt-in retained-session QuickFIX live certification."
    configureLiveCertification(
        "com.simplematch.quickfixgateway.fix.QuickFixRetainedSessionLiveCertificationTest"
    )
}

tasks.register<Test>("preparedSubmissionCertificationTest") {
    group = "verification"
    description = "Runs the opt-in prepared QuickFIX submission certification."
    configureLiveCertification(
        "com.simplematch.quickfixgateway.fix.QuickFixPreparedSubmissionLiveCertificationTest"
    )
}
