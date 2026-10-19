package com.simplematch.gradle

import java.nio.file.Files
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class FlywayDependencyPreparationTest {
    @Test
    fun `prepares the real Flyway classpath offline without database settings`() {
        withFixture(publishDriver = true) { runner ->
            val result = runner.build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":prepareFlywayRuntime")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":prepareFlywayRuntimeAll")?.outcome)
        }
    }

    @Test
    fun `fails when the migration classpath cannot be resolved`() {
        withFixture(publishDriver = false) { runner ->
            val result = runner.buildAndFail()
            assertContains(result.output, "fixture:driver:1.0")
            assertContains(result.output, ":prepareFlywayRuntime FAILED")
        }
    }

    private fun withFixture(publishDriver: Boolean, verify: (GradleRunner) -> Unit) {
        val directory = Files.createTempDirectory("simplematch-flyway-preparation")
        try {
            val repository = directory.resolve("repository")
            Files.createDirectories(repository)
            if (publishDriver) {
                val module = repository.resolve("fixture/driver/1.0")
                Files.createDirectories(module)
                ZipOutputStream(Files.newOutputStream(module.resolve("driver-1.0.jar"))).close()
                Files.writeString(module.resolve("driver-1.0.pom"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
                    <artifactId>driver</artifactId><version>1.0</version></project>
                """.trimIndent())
            }
            Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'flyway-fixture'\n")
            Files.writeString(directory.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'simplematch.flyway-service'
                }
                repositories { maven { url = uri('repository') } }
                configurations { migrationRuntime }
                dependencies { migrationRuntime 'fixture:driver:1.0' }
                simpleMatchFlyway { serviceId = 'account-service' }
                flyway { configurations = ['migrationRuntime'] }
            """.trimIndent())
            verify(GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments("prepareFlywayRuntimeAll", "--offline", "--stacktrace"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
