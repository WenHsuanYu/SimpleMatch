package com.simplematch.gradle

import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.kotlin.dsl.getByType

/** Adds the repository's blocking Java quality policy to a Java module. */
class SimpleMatchJavaQualityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("checkstyle")
        project.pluginManager.apply("pmd")
        project.pluginManager.apply("com.github.spotbugs")

        val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        project.extensions.getByType<CheckstyleExtension>().apply {
            toolVersion = catalog.findVersion("checkstyle").get().requiredVersion
            configDirectory.set(project.rootProject.layout.projectDirectory.dir("config/checkstyle"))
            configProperties["checkstyle.suppressions.file"] =
                project.rootProject.layout.projectDirectory
                    .file("config/checkstyle/suppressions.xml")
                    .asFile
                    .absolutePath
            maxWarnings = 0
        }
        project.extensions.getByType<PmdExtension>().apply {
            toolVersion = catalog.findVersion("pmd").get().requiredVersion
            ruleSets = emptyList()
            ruleSetFiles =
                project.files(project.rootProject.layout.projectDirectory.file(PmdPolicy.RULESET_PATH))
        }
        project.extensions.getByType<SpotBugsExtension>().apply {
            toolVersion.set(catalog.findVersion("spotbugs-tool").get().requiredVersion)
            showProgress.set(false)
            excludeFilter.set(project.rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"))
        }

        project.tasks.withType(Checkstyle::class.java).configureEach {
            source =
                project.fileTree(project.projectDir.resolve("src/main/java")) { include("**/*.java") }
            exclude("**/build/generated/**")
            exclude("**/generated/**")
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        project.tasks.withType(Pmd::class.java).configureEach {
            source =
                project.fileTree(project.projectDir.resolve("src/main/java")) { include("**/*.java") }
            exclude("**/build/generated/**")
            exclude("**/generated/**")

            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        project.tasks
            .matching { it.name == "checkstyleTest" || it.name == "pmdTest" || it.name == "spotbugsTest" }
            .configureEach {
                enabled = false
            }
        project.tasks.withType(SpotBugsTask::class.java).configureEach {
            reports {
                create("xml") {
                    required.set(true)
                }
                create("html") {
                    required.set(true)
                }
            }
        }
        project.tasks.named("check") {
            dependsOn("checkstyleMain", "pmdMain", "spotbugsMain")
        }
        project.rootProject.tasks.named("staticAnalysis") {
            dependsOn(
                "${project.path}:checkstyleMain",
                "${project.path}:pmdMain",
                "${project.path}:spotbugsMain"
            )
        }

        registerCompletedParameterSafetyGate(project)
    }

    private fun registerCompletedParameterSafetyGate(project: Project) {
        val sourceDirectories = PmdPolicy.completedParameterSafetySourceDirectories(project.path)
        if (sourceDirectories.isEmpty()) {
            return
        }

        val task = project.tasks.register("parameterSafetyMain", Pmd::class.java) {
            description =
                "Checks completed Account Authority, Risk Admission, and Risk Submission outbox slices " +
                    "for Java members over seven parameters."
            group = "verification"
            ruleSets = emptyList()
            ruleSetFiles =
                project.files(
                    project.rootProject.layout.projectDirectory.file(
                        PmdPolicy.COMPLETED_PARAMETER_SAFETY_RULESET_PATH
                    )
                )
            source = project.fileTree(project.projectDir) {
                sourceDirectories.forEach { include("$it/**/*.java") }
            }
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        project.tasks.named("check") {
            dependsOn(task)
        }
        project.rootProject.tasks.named("staticAnalysis") {
            dependsOn(task)
        }
    }
}
