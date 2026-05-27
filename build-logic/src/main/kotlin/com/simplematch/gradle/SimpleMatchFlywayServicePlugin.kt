package com.simplematch.gradle

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.inject.Inject
import org.flywaydb.gradle.FlywayExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

abstract class SimpleMatchFlywayExtension @Inject constructor(objects: ObjectFactory) {
  val serviceName: Property<String> = objects.property(String::class.java)
  val migrationLocations: ListProperty<String> = objects.listProperty(String::class.java)
  val baselineVersion: Property<String> = objects.property(String::class.java)
  val schemaName: Property<String> = objects.property(String::class.java)
  val cleanDisabled: Property<Boolean> = objects.property(Boolean::class.java)

  init {
    cleanDisabled.convention(true)
  }
}

class SimpleMatchFlywayServicePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("org.flywaydb.flyway")

    val extension = project.extensions.create<SimpleMatchFlywayExtension>("simpleMatchFlyway")

    project.afterEvaluate {
      val configuredServiceName = extension.serviceName.orNull
          ?: throw IllegalStateException("simpleMatchFlyway.serviceName must be configured for ${project.path}")
      val migrationLocations = extension.migrationLocations.orNull
          ?.takeIf { it.isNotEmpty() }
          ?: throw IllegalStateException("simpleMatchFlyway.migrationLocations must be configured for ${project.path}")
      val configuredBaselineVersion = extension.baselineVersion.orNull
          ?: throw IllegalStateException("simpleMatchFlyway.baselineVersion must be configured for ${project.path}")
      val configuredSchemaName = resolveSchema(project, configuredServiceName, extension)

      project.extensions.configure(FlywayExtension::class.java) {
        baselineOnMigrate = true
        baselineVersion = configuredBaselineVersion
        cleanDisabled = resolveCleanDisabled(project, configuredServiceName, extension)
        locations = migrationLocations.toTypedArray()
        if (configuredSchemaName != null) {
          defaultSchema = configuredSchemaName
          schemas = arrayOf(configuredSchemaName)
        }
      }

      configureFlywayTasks(project, configuredServiceName, extension)
      registerRootTasks(project, configuredServiceName)
    }
  }

  private fun configureFlywayTasks(
      project: Project,
      serviceName: String,
      extension: SimpleMatchFlywayExtension) {
    project.tasks.matching { it.name.startsWith("flyway") }.configureEach {
      group = "database"
      doFirst {
        val connection = resolveConnection(project, serviceName)
        val schemaName = resolveSchema(project, serviceName, extension)
        project.extensions.configure(FlywayExtension::class.java) {
          baselineOnMigrate = true
          baselineVersion = extension.baselineVersion.get()
          cleanDisabled = resolveCleanDisabled(project, serviceName, extension)
          locations = extension.migrationLocations.get().toTypedArray()
          url = connection.jdbcUrl
          user = connection.username
          password = connection.password
          if (schemaName != null) {
            defaultSchema = schemaName
            schemas = arrayOf(schemaName)
          }
        }
      }
    }
  }

  private fun resolveSchema(
      project: Project,
      serviceName: String,
      extension: SimpleMatchFlywayExtension): String? {
    val envPrefix = serviceNameToEnvPrefix(serviceName)
    return propertyOrEnv(project, "${serviceName}FlywaySchema", "${envPrefix}_FLYWAY_SCHEMA")
        ?: extension.schemaName.orNull?.takeIf { it.isNotBlank() }
  }

  private fun registerRootTasks(project: Project, serviceName: String) {
    val rootProject = project.rootProject
    val taskMappings = listOf(
        TaskMapping("flywayInfo", serviceName + "FlywayInfo", "Lists Flyway migration state."),
        TaskMapping("flywayMigrate", serviceName + "FlywayMigrate", "Applies Flyway migrations."),
        TaskMapping("flywayValidate", serviceName + "FlywayValidate", "Validates Flyway migrations."),
        TaskMapping("flywayRepair", serviceName + "FlywayRepair", "Repairs Flyway schema history metadata."),
        TaskMapping("flywayBaseline", serviceName + "FlywayBaseline", "Baselines an existing schema for Flyway."),
        TaskMapping("flywayClean", serviceName + "FlywayClean", "Drops the Flyway-managed schema."))

    taskMappings.forEach { mapping ->
      if (rootProject.tasks.findByName(mapping.rootTaskName) == null) {
        rootProject.tasks.register(mapping.rootTaskName) {
          group = "database"
          description = mapping.description.removeSuffix(".") + " for ${project.path}."
          dependsOn(project.tasks.named(mapping.projectTaskName))
        }
      }

      val aggregateTaskName = mapping.projectTaskName + "All"
      val aggregateTask = rootProject.tasks.findByName(aggregateTaskName)
      if (aggregateTask == null) {
        rootProject.tasks.register(aggregateTaskName) {
          group = "database"
          description = mapping.description.removeSuffix(".") + " for every service using the Flyway convention plugin."
          dependsOn(project.tasks.named(mapping.projectTaskName))
        }
      } else {
        aggregateTask.dependsOn(project.tasks.named(mapping.projectTaskName))
      }
    }
  }

  private fun resolveConnection(project: Project, serviceName: String): FlywayConnection {
    val envPrefix = serviceNameToEnvPrefix(serviceName)
    val dsn = propertyOrEnv(project, "${serviceName}FlywayDsn", "${envPrefix}_FLYWAY_DSN")
        ?: propertyOrEnv(project, "flywayDsn", "SIMPLEMATCH_POSTGRES_DSN")
    if (dsn != null) {
      return parseDsn(dsn)
    }

    val jdbcUrl = propertyOrEnv(project, "${serviceName}FlywayJdbcUrl", "${envPrefix}_FLYWAY_JDBC_URL")
        ?: propertyOrEnv(project, "flywayJdbcUrl", "FLYWAY_JDBC_URL")
        ?: propertyOrEnv(project, "flywayUrl", "FLYWAY_URL")
        ?: throw IllegalStateException(
            "Missing Flyway connection settings for ${project.path}; set -P${serviceName}FlywayDsn, "
                + "-P${serviceName}FlywayJdbcUrl, or the matching environment variables.")

    return FlywayConnection(
        jdbcUrl,
        propertyOrEnv(project, "${serviceName}FlywayUsername", "${envPrefix}_FLYWAY_USERNAME")
            ?: propertyOrEnv(project, "flywayUsername", "FLYWAY_USERNAME")
            ?: propertyOrEnv(project, "flywayUser", "FLYWAY_USER"),
        propertyOrEnv(project, "${serviceName}FlywayPassword", "${envPrefix}_FLYWAY_PASSWORD")
            ?: propertyOrEnv(project, "flywayPassword", "FLYWAY_PASSWORD"))
  }

  private fun resolveCleanDisabled(
      project: Project,
      serviceName: String,
      extension: SimpleMatchFlywayExtension): Boolean {
    val envPrefix = serviceNameToEnvPrefix(serviceName)
    val allowClean = propertyOrEnv(project, "${serviceName}FlywayAllowClean", "${envPrefix}_FLYWAY_ALLOW_CLEAN")
        ?.lowercase(Locale.ROOT)
        ?.let { value ->
          when (value) {
            "true" -> true
            "false" -> false
            else -> throw IllegalStateException(
                "Invalid boolean for ${serviceName}FlywayAllowClean/${envPrefix}_FLYWAY_ALLOW_CLEAN: $value")
          }
        }
    return if (allowClean == true) false else extension.cleanDisabled.get()
  }

  private fun propertyOrEnv(project: Project, propertyName: String, envName: String): String? {
    val propertyValue = project.findProperty(propertyName) as String?
    if (!propertyValue.isNullOrBlank()) {
      return propertyValue
    }
    val environmentValue = System.getenv(envName)
    return environmentValue?.takeIf { it.isNotBlank() }
  }

  private fun parseDsn(rawValue: String): FlywayConnection {
    if (rawValue.startsWith("jdbc:")) {
      return FlywayConnection(rawValue, null, null)
    }

    try {
      val uri = URI(rawValue)
      val scheme = uri.scheme.orEmpty()
      if (scheme != "postgresql" && scheme != "postgres") {
        throw IllegalStateException("Unsupported Flyway DSN scheme: $rawValue")
      }

      val userInfo = uri.userInfo.orEmpty()
      val userParts = userInfo.split(":", limit = 2)
      val username = userParts.firstOrNull()?.takeIf { it.isNotBlank() }
      val password = userParts.getOrNull(1)?.takeIf { it.isNotBlank() }
      val host = uri.host ?: throw IllegalStateException("Flyway DSN host is missing: $rawValue")
      val port = if (uri.port > 0) uri.port else 5432
      val path = uri.path?.takeIf { it.isNotBlank() }
          ?: throw IllegalStateException("Flyway DSN database is missing: $rawValue")

      return FlywayConnection(
          jdbcUrl = "jdbc:postgresql://$host:$port$path",
          username = username,
          password = password)
    } catch (syntaxException: URISyntaxException) {
      throw IllegalStateException("Failed to parse Flyway DSN: $rawValue", syntaxException)
    }
  }

  private fun serviceNameToEnvPrefix(serviceName: String): String =
      serviceName
          .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
          .replace('-', '_')
          .uppercase(Locale.ROOT)

  private data class FlywayConnection(
      val jdbcUrl: String,
      val username: String?,
      val password: String?)

  private data class TaskMapping(
      val projectTaskName: String,
      val rootTaskName: String,
      val description: String)
}