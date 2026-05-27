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
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

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

val mockitoCoreVersion = "5.17.0"

val checkstyleAndSpotbugsProjects = setOf(
    ":shared-java:simplematch-config",
    ":services:account-service",
  ":services:persistence",
    ":services:quickfix-gateway",
    ":services:risk-service")

val staticAnalysisTask = tasks.register("staticAnalysis") {
  group = "verification"
  description =
      "Runs blocking Error Prone compilation for every Java module, plus Checkstyle and SpotBugs for curated modules."
}

subprojects {
  group = rootProject.group
  version = rootProject.version

  plugins.withType<JavaPlugin> {
    val projectPath = project.path

    extensions.configure(JavaPluginExtension::class.java) {
      toolchain {
        languageVersion = JavaLanguageVersion.of(25)
      }
    }

    val mockitoAgent = configurations.create("mockitoAgent")

    dependencies.add("mockitoAgent", "org.mockito:mockito-core:$mockitoCoreVersion") {
      isTransitive = false
    }

    tasks.withType(Test::class.java).configureEach {
      useJUnitPlatform()
      jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        @get:Classpath
        val mockitoAgentClasspath = mockitoAgent

        override fun asArguments(): Iterable<String> =
            listOf("-javaagent:${mockitoAgentClasspath.singleFile.absolutePath}")
      })
    }

    pluginManager.apply("net.ltgt.errorprone")
    dependencies.add("errorprone", "com.google.errorprone:error_prone_core:2.39.0")

    rootProject.tasks.named(staticAnalysisTask.name) {
      dependsOn("$projectPath:classes", "$projectPath:testClasses")
    }

    tasks.withType(JavaCompile::class.java).configureEach {
      options.encoding = "UTF-8"
      options.errorprone.disableWarningsInGeneratedCode.set(true)
      options.errorprone.excludedPaths.set(".*/build/generated(?:/.+)?")
      options.errorprone.errorproneArgs.addAll(
          listOf(
              "-Xep:MissingOverride:ERROR",
              "-Xep:EqualsGetClass:ERROR",
              "-Xep:FutureReturnValueIgnored:ERROR"))
    }

    if (path in checkstyleAndSpotbugsProjects) {
      pluginManager.apply("checkstyle")
      pluginManager.apply("com.github.spotbugs")

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

      rootProject.tasks.named(staticAnalysisTask.name) {
        dependsOn("$projectPath:checkstyleMain", "$projectPath:spotbugsMain")
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