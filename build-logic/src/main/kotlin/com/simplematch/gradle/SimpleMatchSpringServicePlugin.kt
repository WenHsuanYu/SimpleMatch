package com.simplematch.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
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

        val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        val springBootBom = catalog.findLibrary("spring-boot-bom").get()
        val springCloudBom = catalog.findLibrary("spring-cloud-bom").get()
        val lombok = catalog.findLibrary("lombok").get()

        project.dependencies.apply {
            add("implementation", platform(springBootBom))
            add("implementation", platform(springCloudBom))

            add(
                "implementation",
                project.project(":shared-java:simplematch-config")
            )
            add(
                "implementation",
                project.project(":shared-java:simplematch-contracts")
            )

            add(
                "implementation",
                "org.springframework.boot:spring-boot-starter"
            )
            add(
                "implementation",
                "org.springframework.boot:spring-boot-starter-actuator"
            )
            add(
                "implementation",
                "org.springframework.boot:spring-boot-starter-validation"
            )
            add(
                "implementation",
                "org.springframework.cloud:spring-cloud-starter-kubernetes-client-config"
            )

            add("runtimeOnly", "org.postgresql:postgresql")

            add(
                "compileOnly",
                catalog.findLibrary("jakarta-annotation-api").get()
            )

            add("compileOnly", lombok)

            /*
             * annotationProcessor is an independent dependency graph.
             * It must receive the Boot BOM explicitly so versionless
             * processors such as Lombok and the Spring Boot configuration
             * processor can be resolved.
             */
            add("annotationProcessor", platform(springBootBom))
            add("annotationProcessor", lombok)

            add("testCompileOnly", lombok)

            /*
             * testAnnotationProcessor is also independent from both
             * implementation and annotationProcessor.
             */
            add("testAnnotationProcessor", platform(springBootBom))
            add("testAnnotationProcessor", lombok)

            add(
                "testImplementation",
                "org.springframework.boot:spring-boot-starter-test"
            )
        }
    }
}
