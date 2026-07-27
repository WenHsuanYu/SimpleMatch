package com.simplematch.gradle

import com.google.protobuf.gradle.ProtobufExtension
import com.google.protobuf.gradle.proto
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/** Applies the repository's stable protobuf and gRPC Java generation contract. */
class SimpleMatchProtobufContractsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("simplematch.java-conventions")
    project.pluginManager.apply("java-library")
    project.pluginManager.apply("com.google.protobuf")

    val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    project.extensions.configure<JavaPluginExtension> {
      withSourcesJar()
    }
    project.extensions.configure<SourceSetContainer> {
      named("main") {
        proto {
          srcDir("../../proto")
          include("common.proto")
          include("orders.proto")
          include("matching.proto")
          include("marketdata.proto")
          include("account_service.proto")
          include("risk_service.proto")
          include("marketdata_service.proto")
        }
      }
    }
    project.extensions.configure<ProtobufExtension> {
      protoc {
        artifact = catalog.coordinate("protobuf-protoc")
      }
      plugins {
        create("grpc") {
          artifact = catalog.coordinate("grpc-protoc-gen-java")
        }
      }
      generateProtoTasks {
        all().configureEach {
          plugins {
            create("grpc")
          }
        }
      }
    }
    project.dependencies.apply {
      add("api", catalog.findLibrary("protobuf-java").get())
      add("api", catalog.findLibrary("grpc-protobuf").get())
      add("api", catalog.findLibrary("grpc-stub").get())
      add("compileOnly", catalog.findLibrary("jakarta-annotation-api").get())
      add("testImplementation", catalog.findLibrary("junit-jupiter").get())
      add("testRuntimeOnly", catalog.findLibrary("junit-jupiter-engine").get())
      add("testRuntimeOnly", catalog.findLibrary("junit-platform-launcher").get())
    }
  }

  private fun VersionCatalog.coordinate(alias: String): String {
    val dependency = findLibrary(alias).get().get()
    return "${dependency.module.group}:${dependency.module.name}:${dependency.versionConstraint.requiredVersion}"
  }
}
