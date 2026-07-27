package com.simplematch.gradle

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * Applies the dependencies and plugins shared by every Spring Boot service in this repository.
 *
 * Service-specific capabilities such as JDBC, Kafka, gRPC, QuickFIX/J, and Flyway remain in the
 * consuming build so that each service keeps its operational dependencies explicit.
 */
class SimpleMatchSpringServicePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("simplematch.java-conventions")
    project.pluginManager.apply("simplematch.java-quality")
    project.pluginManager.apply("org.springframework.boot")
    project.pluginManager.apply("io.spring.dependency-management")

    val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    val dependencyManagement =
        project.extensions.getByType<DependencyManagementExtension>()
    dependencyManagement.imports {
      mavenBom(
          "org.springframework.cloud:spring-cloud-dependencies:"
              + catalog.findVersion("spring-cloud").get().requiredVersion)
    }

    project.dependencies.apply {
      add("implementation", project.project(":shared-java:simplematch-config"))
      add("implementation", project.project(":shared-java:simplematch-contracts"))
      add("implementation", "org.springframework.boot:spring-boot-starter")
      add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
      add("implementation", "org.springframework.boot:spring-boot-starter-validation")
      add("implementation", "org.springframework.cloud:spring-cloud-starter-kubernetes-client-config")
      add("runtimeOnly", "org.postgresql:postgresql")
      add("compileOnly", catalog.findLibrary("jakarta-annotation-api").get())
      add("annotationProcessor", catalog.findLibrary("lombok").get())
      add("compileOnly", catalog.findLibrary("lombok").get())
      add("testCompileOnly", catalog.findLibrary("lombok").get())
      add("testAnnotationProcessor", catalog.findLibrary("lombok").get())
      add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
    }
  }
}
