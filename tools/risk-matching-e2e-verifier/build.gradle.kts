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
