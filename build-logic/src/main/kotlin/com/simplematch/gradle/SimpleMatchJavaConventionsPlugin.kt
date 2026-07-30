package com.simplematch.gradle

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.getByType
import org.gradle.process.CommandLineArgumentProvider

/** Applies Java compilation, test-runtime, and Error Prone policy shared by Java modules. */
class SimpleMatchJavaConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java")

        val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        project.extensions.getByType<JavaPluginExtension>().toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        project.dependencyLocking {
            lockAllConfigurations()
        }

        val mockitoAgent = project.configurations.maybeCreate("mockitoAgent")
        val mockitoDependency = project.dependencies.create(catalog.coordinate("mockito-core"))
        (mockitoDependency as ExternalModuleDependency).isTransitive = false
        project.dependencies.add("mockitoAgent", mockitoDependency)
        project.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
            jvmArgumentProviders.add(MockitoAgentArgumentProvider(mockitoAgent))
        }

        project.pluginManager.apply("net.ltgt.errorprone")
        project.dependencies.add("errorprone", catalog.coordinate("errorprone-core"))
        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
            options.errorprone {
                allErrorsAsWarnings.set(true)
                disableWarningsInGeneratedCode.set(true)
                excludedPaths.set(".*/(?:build/)?generated(?:/.*)?")
                enabled.set(name != "compileGeneratedJava")
                errorproneArgs.addAll(
                    listOf(
                        "-Xep:MissingOverride:WARN",
                        "-Xep:EqualsGetClass:WARN",
                        "-Xep:FutureReturnValueIgnored:WARN"
                    )
                )
            }
        }

        registerStaticAnalysisDependency(project)
    }

    private fun registerStaticAnalysisDependency(project: Project) {
        val rootProject = project.rootProject
        if (rootProject.tasks.findByName("staticAnalysis") == null) {
            rootProject.tasks.register("staticAnalysis") {
                group = "verification"
                description =
                    "Runs Error Prone warning compilation for every Java module, plus blocking configured quality checks."
            }
        }
        rootProject.tasks.named("staticAnalysis") {
            dependsOn("${project.path}:classes", "${project.path}:testClasses")
        }
    }

    private fun VersionCatalog.coordinate(alias: String): String {
        val dependency = findLibrary(alias).get().get()
        val module = "${dependency.module.group}:${dependency.module.name}"
        return dependency.versionConstraint.requiredVersion.takeIf(String::isNotBlank)?.let { "$module:$it" } ?: module
    }

    private class MockitoAgentArgumentProvider(
        @get:Classpath private val mockitoAgentClasspath: org.gradle.api.file.FileCollection
    ) :
        CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> =
            listOf("-javaagent:${mockitoAgentClasspath.singleFile.absolutePath}")
    }
}
