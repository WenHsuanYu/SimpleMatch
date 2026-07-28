package com.simplematch.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PostgresJdbcUrlTest {
    @Test
    void parsesPostgresUriCredentialsIntoJdbcConnectionSettings() {
        final PostgresJdbcUrl settings = PostgresJdbcUrl.parse("postgresql://alice:secret@db.example:5433/simplematch");

        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:5433/simplematch");
        assertThat(settings.username()).isEqualTo("alice");
        assertThat(settings.password()).isEqualTo("secret");
    }

    @Test
    void preservesJdbcUrlsWithoutInventingCredentials() {
        final PostgresJdbcUrl settings = PostgresJdbcUrl.parse("jdbc:postgresql://localhost:5432/simplematch");

        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/simplematch");
        assertThat(settings.username()).isNull();
        assertThat(settings.password()).isNull();
    }

    @Test
    void rejectsBlankAndUnsupportedDataSources() {
        assertThatIllegalStateException().isThrownBy(() -> PostgresJdbcUrl.parse(" "));
        assertThatIllegalStateException()
                .isThrownBy(() -> PostgresJdbcUrl.parse("mysql://localhost:3306/simplematch"));
    }

    @Test
    void rejectsPostgresUrisWithoutHostOrDatabase() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PostgresJdbcUrl.parse("postgresql:///simplematch"));
        assertThatIllegalStateException()
                .isThrownBy(() -> PostgresJdbcUrl.parse("postgresql://localhost"));
    }
}
