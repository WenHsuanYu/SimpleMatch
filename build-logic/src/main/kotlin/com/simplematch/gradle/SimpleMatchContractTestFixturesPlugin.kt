package com.simplematch.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension

/** Adds the repository-owned contract fixtures to a Java module's test resources. */
class SimpleMatchContractTestFixturesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java") {
            project.extensions
                .getByType(JavaPluginExtension::class.java)
                .sourceSets
                .getByName("test")
                .resources
                .srcDir(
                    project.rootProject.file(
                        "shared-java/simplematch-contracts/src/test/resources"
                    )
                )
        }
    }
}
