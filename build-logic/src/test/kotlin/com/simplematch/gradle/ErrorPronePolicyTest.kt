package com.simplematch.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class ErrorPronePolicyTest {
    @Test
    fun `error prone finding fails the static analysis lifecycle`() {
        withFixture(
            sourcePath = "src/main/java/fixture/MissingOverrideFixture.java",
            source = missingOverrideSource()
        ) { runner ->
            val result = runner
                .withArguments("staticAnalysis", "--stacktrace")
                .buildAndFail()

            assertContains(result.output, "MissingOverride")
            assertContains(result.output, ":compileJava FAILED")
            assertContains(result.output, "Compilation failed")
        }
    }

    @Test
    fun `generated source boundary remains outside error prone gate`() {
        withFixture(
            sourcePath = "src/main/java/generated/MissingOverrideFixture.java",
            source = missingOverrideSource(),
            registerGeneratedCompileTask = true
        ) { runner ->
            val result = runner
                .withArguments("compileJava", "compileGeneratedJava", "--stacktrace")
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":compileGeneratedJava")?.outcome)
        }
    }

    private fun withFixture(
        sourcePath: String,
        source: String,
        registerGeneratedCompileTask: Boolean = false,
        verification: (GradleRunner) -> Unit
    ) {
        val projectDirectory = createFixture(sourcePath, source, registerGeneratedCompileTask)
        try {
            verification(runner(projectDirectory))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    private fun createFixture(
        sourcePath: String,
        source: String,
        registerGeneratedCompileTask: Boolean = false
    ): Path {
        val projectDirectory = Files.createTempDirectory("simplematch-error-prone-fixture")
        val sourceFile = projectDirectory.resolve(sourcePath)
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, source)
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), settingsScript())
        Files.writeString(
            projectDirectory.resolve("build.gradle.kts"),
            buildScript(registerGeneratedCompileTask)
        )
        return projectDirectory
    }

    private fun runner(projectDirectory: Path): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()

    private fun settingsScript(): String {
        val repositoryRoot = Path.of(
            System.getProperty("simplematch.repositoryRoot")
        )
        val buildLogic = repositoryRoot.resolve("build-logic").toGradlePath()
        val catalog = repositoryRoot.resolve("gradle/libs.versions.toml").toGradlePath()
        return """
            pluginManagement {
                includeBuild("$buildLogic")
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
                versionCatalogs {
                    create("libs") {
                        from(files("$catalog"))
                    }
                }
            }

            rootProject.name = "error-prone-fixture"
        """.trimIndent()
    }

    private fun buildScript(registerGeneratedCompileTask: Boolean): String {
        val generatedTask = if (registerGeneratedCompileTask) {
            """
                import org.gradle.api.tasks.compile.JavaCompile

                tasks.register<JavaCompile>("compileGeneratedJava") {
                    source(fileTree("src/main/java/generated"))
                    destinationDirectory.set(layout.buildDirectory.dir("classes/generated"))
                    classpath = files()
                }
            """.trimIndent()
        } else {
            ""
        }
        return """
            plugins {
                id("simplematch.java-conventions")
            }

            $generatedTask
        """.trimIndent()
    }

    private fun missingOverrideSource(): String =
        """
            package fixture;

            class MissingOverrideFixture extends Parent {
                public void execute() {}
            }

            class Parent {
                public void execute() {}
            }
        """.trimIndent()

    private fun Path.toGradlePath(): String =
        toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"")
}
