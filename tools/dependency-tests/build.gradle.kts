import com.simplematch.gradle.SimpleMatchFlywayExtension
import groovy.json.JsonOutput
import org.flywaydb.gradle.FlywayExtension

plugins {
    id("simplematch.java-conventions")
    id("simplematch.java-quality")
}

dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.flyway.core)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework:spring-jdbc")
    testRuntimeOnly(libs.flyway.database.postgresql)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

fun ownerConfiguration() = rootProject.subprojects
    .filter { it.plugins.hasPlugin("simplematch.flyway-service") }
    .associate { owner ->
        val identity = owner.extensions.getByType<SimpleMatchFlywayExtension>().serviceId.get()
        val configuration = owner.extensions.getByType<FlywayExtension>()
        identity to mapOf(
            "flyway.locations" to configuration.locations.joinToString(","),
            "flyway.schemas" to configuration.schemas.joinToString(","),
            "flyway.defaultSchema" to configuration.defaultSchema,
            "flyway.baselineOnMigrate" to configuration.baselineOnMigrate.toString(),
            "flyway.baselineVersion" to configuration.baselineVersion,
            "flyway.cleanDisabled" to configuration.cleanDisabled.toString()
        )
    }.toSortedMap()

tasks.withType<Test>().configureEach {
    systemProperty("dependencyRepoRoot", rootProject.projectDir.absolutePath)
    inputs.property("flywayOwners", providers.provider { JsonOutput.toJson(ownerConfiguration()) })
    inputs.files(providers.provider {
        ownerConfiguration().values.flatMap { it.getValue("flyway.locations").split(',') }
            .map { fileTree(it.removePrefix("filesystem:")) }
    })
    inputs.file("src/test/resources/postgres-contracts.json")
    inputs.files(listOf("scripts/lib/flyway-services.sh", "deploy/docker/run-flyway",
        "scripts/check-service-schema-contracts.sql",
        "scripts/end-to-end/critical-consumers/sql/gateway-session-state.sql",
        "scripts/end-to-end/critical-consumers/tests/fixtures/gateway-session-padding.sql",
        "scripts/end-to-end/critical-consumers/tests/baselines/gateway-session-identity.json")
        .map(rootProject::file))
    doFirst {
        systemProperty("dependencyFlywayOwners", JsonOutput.toJson(ownerConfiguration()))
    }
}

tasks.test {
    exclude("**/PostgresIntegrationTest.class", "**/PostgresLiveTest.class", "**/PostgresQueryPlansTest.class")
}

fun registerLiveTest(name: String, testClass: String) = tasks.register<Test>(name) {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.simplematch.tools.dependencies.$testClass")
    outputs.upToDateWhen { false }
    maxParallelForks = 1
}

val postgresTest = registerLiveTest("postgresTest", "PostgresIntegrationTest")
postgresTest.configure {
    dependsOn(":prepareFlywayRuntimeAll", gradle.includedBuild("build-logic").task(":test"))
}
registerLiveTest("postgresLiveTest", "PostgresLiveTest")
registerLiveTest("queryPlanTest", "PostgresQueryPlansTest")

rootProject.tasks.register("verifyPostgres") {
    group = "verification"
    description = "Verifies all Flyway owners and Query JDBC contracts with disposable PostgreSQL."
    dependsOn(tasks.test, postgresTest, ":services:query-service:postgresJdbcTest")
}
