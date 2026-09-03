package com.simplematch.gradle

import java.net.URI
import java.net.URISyntaxException

/** Connection settings produced from a Flyway JDBC URL or PostgreSQL URI. */
internal data class FlywayConnection(
    val jdbcUrl: String,
    val username: String?,
    val password: String?
)

/** Normalizes Flyway connection inputs without discarding PostgreSQL URI options. */
internal object FlywayDsnParser {
    /**
     * Parses a JDBC URL or PostgreSQL URI into the connection fields consumed by Flyway.
     *
     * @param rawValue configured JDBC URL or PostgreSQL URI
     * @return normalized Flyway connection settings
     * @throws IllegalStateException if the input is not a supported PostgreSQL DSN
     */
    fun parse(rawValue: String): FlywayConnection {
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
            val host =
                uri.host ?: throw IllegalStateException("Flyway DSN host is missing: $rawValue")
            val port = if (uri.port > 0) uri.port else 5432
            val path =
                uri.path?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Flyway DSN database is missing: $rawValue")
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()

            return FlywayConnection(
                jdbcUrl = "jdbc:postgresql://$host:$port$path$query",
                username = username,
                password = password
            )
        } catch (syntaxException: URISyntaxException) {
            throw IllegalStateException("Failed to parse Flyway DSN: $rawValue", syntaxException)
        }
    }
}
