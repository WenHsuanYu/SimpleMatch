package com.simplematch.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class FlywayDsnParserTest {
    @Test
    fun `preserves PostgreSQL URI query parameters when normalizing to JDBC`() {
        val connection = FlywayDsnParser.parse(
            "postgresql://user:password@db.example:5433/simplematch"
                + "?sslmode=verify-full&sslrootcert=%2Fetc%2Fsimplematch%2Fca.crt"
        )

        assertEquals(
            "jdbc:postgresql://db.example:5433/simplematch"
                + "?sslmode=verify-full&sslrootcert=%2Fetc%2Fsimplematch%2Fca.crt",
            connection.jdbcUrl
        )
        assertEquals("user", connection.username)
        assertEquals("password", connection.password)
    }
}
