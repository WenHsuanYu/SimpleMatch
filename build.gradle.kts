import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.plugins.JavaPluginExtension

plugins {
  base
  id("com.github.spotbugs") version "6.2.4" apply false
  id("net.ltgt.errorprone") version "4.3.0" apply false
}

group = "com.simplematch"
version = "0.1.0-SNAPSHOT"

allprojects {
  repositories {
    mavenCentral()
  }
}

val staticAnalysisProjects = setOf(
    ":java-libs:simplematch-config",
    ":services:account-service",
    ":services:quickfix-gateway",
    ":services:risk-service")

subprojects {
  group = rootProject.group
  version = rootProject.version

  plugins.withType<JavaPlugin> {
    extensions.configure(JavaPluginExtension::class.java) {
      toolchain {
        languageVersion = JavaLanguageVersion.of(25)
      }
    }

    tasks.withType(Test::class.java).configureEach {
      useJUnitPlatform()
    }

    if (path in staticAnalysisProjects) {
      pluginManager.apply("checkstyle")
      pluginManager.apply("com.github.spotbugs")
      pluginManager.apply("net.ltgt.errorprone")

      extensions.configure(CheckstyleExtension::class.java) {
        toolVersion = "10.26.1"
        configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
        configProperties["checkstyle.suppressions.file"] =
            rootProject.layout.projectDirectory.file("config/checkstyle/suppressions.xml").asFile.absolutePath
        isIgnoreFailures = false
        maxWarnings = 0
      }

      extensions.configure(SpotBugsExtension::class.java) {
        toolVersion.set("4.9.4")
        ignoreFailures.set(false)
        showProgress.set(true)
        excludeFilter.set(rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"))
      }

      dependencies.add("errorprone", "com.google.errorprone:error_prone_core:2.39.0")

      tasks.withType(JavaCompile::class.java).configureEach {
        options.encoding = "UTF-8"
        options.errorprone.disableWarningsInGeneratedCode.set(true)
        options.errorprone.excludedPaths.set(".*/build/generated(?:/.+)?")
        options.errorprone.errorproneArgs.addAll(
            listOf(
                "-XepAllErrorsAsWarnings",
                "-Xep:MissingOverride:WARN",
                "-Xep:EqualsGetClass:WARN",
                "-Xep:FutureReturnValueIgnored:WARN"))
      }

      tasks.withType(Checkstyle::class.java).configureEach {
        source("src/main/java")
        include("**/*.java")
        reports {
          xml.required.set(true)
          html.required.set(true)
        }
      }

      tasks.matching { it.name == "checkstyleTest" || it.name == "spotbugsTest" }.configureEach {
        enabled = false
      }

      tasks.withType(SpotBugsTask::class.java).configureEach {
        reports {
          create("xml") {
            required.set(true)
          }
          create("html") {
            required.set(true)
          }
        }
      }

      tasks.named("check") {
        dependsOn("checkstyleMain", "spotbugsMain")
      }
    }
  }
}

tasks.register("staticAnalysis") {
  group = "verification"
  description = "Runs Checkstyle, SpotBugs, and Error Prone backed compilation for analyzed Java modules."
  dependsOn(
      staticAnalysisProjects.flatMap { projectPath ->
        listOf(
            "$projectPath:classes",
            "$projectPath:checkstyleMain",
            "$projectPath:spotbugsMain")
      })
}