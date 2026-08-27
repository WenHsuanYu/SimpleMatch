plugins {
    application
    `java-library`
    id("simplematch.java-conventions")
    id("simplematch.java-quality")

}

val verifierRuntimeClasspath = configurations.named("runtimeClasspath")

dependencies {
    implementation(project(":shared-java:simplematch-contracts"))
    implementation(project(":shared-java:market-reference-contract"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jackson.databind)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation("org.apache.kafka:kafka-clients")

    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)

    compileOnly(libs.spotbugs.annotations)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val verifyLoggingRuntime = tasks.register("verifyLoggingRuntime") {
    group = "verification"
    description = "Verifies the standalone verifier has one Log4j2 SLF4J runtime."

    doLast {
        val coordinates = verifierRuntimeClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.name}" }
            .toSet()
        val required = setOf(
            "org.apache.logging.log4j:log4j-core",
            "org.apache.logging.log4j:log4j-slf4j2-impl"
        )
        val conflicting = setOf(
            "ch.qos.logback:logback-classic",
            "org.slf4j:slf4j-simple",
            "org.slf4j:slf4j-nop",
            "org.slf4j:slf4j-jdk14",
            "org.slf4j:slf4j-reload4j",
            "org.apache.logging.log4j:log4j-to-slf4j"
        ).intersect(coordinates)

        check(coordinates.containsAll(required)) {
            "Verifier runtime is missing required Log4j2 SLF4J artifacts: ${required - coordinates}"
        }
        check(conflicting.isEmpty()) {
            "Verifier runtime contains conflicting logging artifacts: $conflicting"
        }
    }
}

tasks.named("test") {
    dependsOn(verifyLoggingRuntime)
}

application {
    mainClass.set("com.simplematch.tools.riskmatchinge2e.RiskMatchingE2eVerifierMain")
}

tasks.register<JavaExec>("observeMarketDataSnapshot") {
    group = "verification"
    description = "Observes one deployed market-data snapshot through gRPC."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.simplematch.tools.riskmatchinge2e.MarketDataSnapshotObservationMain")
    doFirst {
        val port = System.getenv("SIMPLEMATCH_MARKET_DATA_PORT")
            ?: error("SIMPLEMATCH_MARKET_DATA_PORT is required")
        val venueMic = System.getenv("SIMPLEMATCH_MARKET_DATA_VENUE_MIC")
            ?: error("SIMPLEMATCH_MARKET_DATA_VENUE_MIC is required")
        val symbol = System.getenv("SIMPLEMATCH_MARKET_DATA_SYMBOL")
            ?: error("SIMPLEMATCH_MARKET_DATA_SYMBOL is required")
        val evidence = System.getenv("SIMPLEMATCH_MARKET_DATA_EVIDENCE")
            ?: error("SIMPLEMATCH_MARKET_DATA_EVIDENCE is required")
        args(
            "--host", System.getenv("SIMPLEMATCH_MARKET_DATA_HOST") ?: "127.0.0.1",
            "--port", port,
            "--venue-mic", venueMic,
            "--symbol", symbol,
            "--timeout-seconds", System.getenv("SIMPLEMATCH_MARKET_DATA_TIMEOUT_SECONDS") ?: "60",
            "--evidence", evidence
        )
        System.getenv("SIMPLEMATCH_MARKET_DATA_READY_FILE")?.let {
            args("--ready-file", it)
        }
    }
}
