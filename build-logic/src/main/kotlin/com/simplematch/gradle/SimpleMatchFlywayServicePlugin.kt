package com.simplematch.gradle

import org.flywaydb.gradle.FlywayExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.create
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import javax.inject.Inject

/** Configuration for a Flyway-owning service. */
abstract class SimpleMatchFlywayExtension @Inject constructor(objects: ObjectFactory) {
    /** Stable lowercase kebab-case identity used to derive Flyway defaults. */
    val serviceId: Property<String> = objects.property(String::class.java)

    /** @deprecated Use [serviceId] with a lowercase kebab-case identifier. */
    @Deprecated("Use serviceId")
    val serviceName: Property<String> = objects.property(String::class.java)

    /** Overrides the migration location derived from [serviceId]. */
    val migrationLocations: ListProperty<String> = objects.listProperty(String::class.java)

    /** Overrides the default Flyway baseline version of `1`. */
    val baselineVersion: Property<String> = objects.property(String::class.java)

    /** Optional owner schema for service-local tables. */
    val schemaName: Property<String> = objects.property(String::class.java)

    /** Keeps destructive Flyway clean operations disabled unless explicitly allowed. */
    val cleanDisabled: Property<Boolean> = objects.property(Boolean::class.java)

    init {
        cleanDisabled.convention(true)
    }
}

/** Applies a service-scoped Flyway convention and stable root task aliases. */
class SimpleMatchFlywayServicePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.flywaydb.flyway")

        val extension = project.extensions.create<SimpleMatchFlywayExtension>("simpleMatchFlyway")
        val providers = project.providers
        val flywayExtension = project.extensions.getByType(FlywayExtension::class.java)

        project.afterEvaluate {
            val serviceId = resolveServiceId(extension)
            val serviceTaskPrefix = FlywayServiceIdentity.taskPrefix(serviceId)
            val migrationLocations =
                extension.migrationLocations.orNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.toTypedArray()
                    ?: arrayOf(FlywayServiceIdentity.defaultMigrationLocation(project.projectDir, serviceId))
            val baselineVersion = extension.baselineVersion.orNull ?: "1"
            val fallbackSchemaName = extension.schemaName.orNull?.takeIf { it.isNotBlank() }
            val defaultCleanDisabled = extension.cleanDisabled.get()
            val schemaName = resolveSchema(providers, serviceTaskPrefix, serviceId, fallbackSchemaName)
            val cleanDisabled =
                resolveCleanDisabled(providers, serviceTaskPrefix, serviceId, defaultCleanDisabled)

            val connection =
                if (isFlywayTaskRequested(project, serviceTaskPrefix)) {
                    resolveConnection(providers, serviceTaskPrefix, serviceId, project.path)
                } else {
                    null
                }

            flywayExtension.apply {
                baselineOnMigrate = false
                this.baselineVersion = baselineVersion
                this.cleanDisabled = cleanDisabled
                locations = migrationLocations
                if (connection != null) {
                    url = connection.jdbcUrl
                    user = connection.username
                    password = connection.password
                }
                if (schemaName != null) {
                    defaultSchema = schemaName
                    schemas = arrayOf(schemaName)
                }
            }

            configureFlywayTasks(project)
            registerRootTasks(project, serviceTaskPrefix)
        }
    }

    private fun resolveServiceId(extension: SimpleMatchFlywayExtension): String {
        val configuredServiceId =
            extension.serviceId.orNull
                ?: extension.serviceName.orNull?.let(FlywayServiceIdentity::legacyServiceNameToId)
                ?: throw IllegalStateException("simpleMatchFlyway.serviceId must be configured.")
        return FlywayServiceIdentity.validate(configuredServiceId)
    }

    private fun isFlywayTaskRequested(project: Project, serviceTaskPrefix: String): Boolean {
        val normalizedTaskPrefix = serviceTaskPrefix.lowercase(Locale.ROOT) + "flyway"
        return project.gradle.startParameter.taskNames.any { requested ->
            val normalized = requested.substringAfterLast(':').lowercase(Locale.ROOT)
            requested.startsWith(project.path + ":flyway")
                    || normalized.startsWith(normalizedTaskPrefix)
                    || normalized.startsWith("flyway") && normalized.endsWith("all")
        }
    }

    private fun configureFlywayTasks(project: Project) {
        project.tasks.matching { it.name.startsWith("flyway") }.configureEach {
            group = "database"
        }
    }

    private fun resolveSchema(
        providers: ProviderFactory,
        serviceTaskPrefix: String,
        serviceId: String,
        fallbackSchemaName: String?
    ): String? {
        val envPrefix = serviceIdToEnvPrefix(serviceId)
        return propertyOrEnv(providers, "${serviceTaskPrefix}FlywaySchema", "${envPrefix}_FLYWAY_SCHEMA")
            ?: fallbackSchemaName
    }

    private fun registerRootTasks(project: Project, serviceTaskPrefix: String) {
        val rootProject = project.rootProject
        val taskMappings =
            listOf(
                TaskMapping("flywayInfo", serviceTaskPrefix + "FlywayInfo", "Lists Flyway migration state."),
                TaskMapping("flywayMigrate", serviceTaskPrefix + "FlywayMigrate", "Applies Flyway migrations."),
                TaskMapping("flywayValidate", serviceTaskPrefix + "FlywayValidate", "Validates Flyway migrations."),
                TaskMapping(
                    "flywayRepair",
                    serviceTaskPrefix + "FlywayRepair",
                    "Repairs Flyway schema history metadata."
                ),
                TaskMapping(
                    "flywayBaseline",
                    serviceTaskPrefix + "FlywayBaseline",
                    "Baselines an existing schema for Flyway."
                ),
                TaskMapping("flywayClean", serviceTaskPrefix + "FlywayClean", "Drops the Flyway-managed schema.")
            )

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
                    description =
                        mapping.description.removeSuffix(".") + " for every service using the Flyway convention plugin."
                    dependsOn(project.tasks.named(mapping.projectTaskName))
                }
            } else {
                aggregateTask.dependsOn(project.tasks.named(mapping.projectTaskName))
            }
        }
    }

    private fun resolveConnection(
        providers: ProviderFactory,
        serviceTaskPrefix: String,
        serviceId: String,
        projectPath: String
    ): FlywayConnection {
        val envPrefix = serviceIdToEnvPrefix(serviceId)
        val dsn =
            propertyOrEnv(providers, "${serviceTaskPrefix}FlywayDsn", "${envPrefix}_FLYWAY_DSN")
                ?: propertyOrEnv(providers, "flywayDsn", "SIMPLEMATCH_POSTGRES_DSN")
        if (dsn != null) {
            return parseDsn(dsn)
        }

        val jdbcUrl =
            propertyOrEnv(providers, "${serviceTaskPrefix}FlywayJdbcUrl", "${envPrefix}_FLYWAY_JDBC_URL")
                ?: propertyOrEnv(providers, "flywayJdbcUrl", "FLYWAY_JDBC_URL")
                ?: propertyOrEnv(providers, "flywayUrl", "FLYWAY_URL")
                ?: throw IllegalStateException(
                    "Missing Flyway connection settings for ${projectPath}; set -P${serviceTaskPrefix}FlywayDsn, "
                            + "-P${serviceTaskPrefix}FlywayJdbcUrl, or the matching environment variables."
                )

        return FlywayConnection(
            jdbcUrl,
            propertyOrEnv(providers, "${serviceTaskPrefix}FlywayUsername", "${envPrefix}_FLYWAY_USERNAME")
                ?: propertyOrEnv(providers, "flywayUsername", "FLYWAY_USERNAME")
                ?: propertyOrEnv(providers, "flywayUser", "FLYWAY_USER"),
            propertyOrEnv(providers, "${serviceTaskPrefix}FlywayPassword", "${envPrefix}_FLYWAY_PASSWORD")
                ?: propertyOrEnv(providers, "flywayPassword", "FLYWAY_PASSWORD")
        )
    }

    private fun resolveCleanDisabled(
        providers: ProviderFactory,
        serviceTaskPrefix: String,
        serviceId: String,
        defaultCleanDisabled: Boolean
    ): Boolean {
        val envPrefix = serviceIdToEnvPrefix(serviceId)
        val allowClean =
            propertyOrEnv(providers, "${serviceTaskPrefix}FlywayAllowClean", "${envPrefix}_FLYWAY_ALLOW_CLEAN")
                ?.lowercase(Locale.ROOT)
                ?.let { value ->
                    when (value) {
                        "true" -> true
                        "false" -> false
                        else ->
                            throw IllegalStateException(
                                "Invalid boolean for ${serviceTaskPrefix}FlywayAllowClean/${envPrefix}_FLYWAY_ALLOW_CLEAN: $value"
                            )
                    }
                }
        return if (allowClean == true) false else defaultCleanDisabled
    }

    private fun propertyOrEnv(
        providers: ProviderFactory,
        propertyName: String,
        envName: String
    ): String? =
        providers.gradleProperty(propertyName).orNull ?: providers.environmentVariable(envName).orNull

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
            val path =
                uri.path?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Flyway DSN database is missing: $rawValue")

            return FlywayConnection(
                jdbcUrl = "jdbc:postgresql://$host:$port$path", username = username, password = password
            )
        } catch (syntaxException: URISyntaxException) {
            throw IllegalStateException("Failed to parse Flyway DSN: $rawValue", syntaxException)
        }
    }

    private fun serviceIdToEnvPrefix(serviceId: String): String =
        serviceId.replace('-', '_').uppercase(Locale.ROOT)

    private data class FlywayConnection(
        val jdbcUrl: String,
        val username: String?,
        val password: String?
    )

    private data class TaskMapping(
        val projectTaskName: String,
        val rootTaskName: String,
        val description: String
    )
}

/** Stable naming functions used by the Flyway convention and its compatibility aliases. */
internal object FlywayServiceIdentity {
    private val serviceIdPattern = Regex("[a-z][a-z0-9-]*")

    fun validate(serviceId: String): String {
        require(serviceIdPattern.matches(serviceId)) {
            "Flyway serviceId must be lowercase kebab-case: $serviceId"
        }
        return serviceId
    }

    fun taskPrefix(serviceId: String): String =
        validate(serviceId)
            .split('-')
            .joinToString("") { segment -> segment.replaceFirstChar(Char::uppercaseChar) }
            .replaceFirstChar(Char::lowercaseChar)

    fun defaultMigrationLocation(projectDir: File, serviceId: String): String =
        "filesystem:${projectDir}/src/main/resources/db/migration/${validate(serviceId)}"

    fun legacyServiceNameToId(serviceName: String): String =
        serviceName.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase(Locale.ROOT)
}
