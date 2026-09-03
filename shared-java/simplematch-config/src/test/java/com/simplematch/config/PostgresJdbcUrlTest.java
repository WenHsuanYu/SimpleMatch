package com.simplematch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class PostgresJdbcUrlTest {
  @Test
  void parsesPostgresUriCredentialsIntoJdbcConnectionSettings() {
    final PostgresJdbcUrl settings =
        PostgresJdbcUrl.parse("postgresql://alice:secret@db.example:5433/simplematch");

    assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:5433/simplematch");
    assertThat(settings.username()).isEqualTo("alice");
    assertThat(settings.password()).isEqualTo("secret");
  }

  @Test
  void acceptsThePostgresUriSchemeAlias() {
    final PostgresJdbcUrl settings =
        PostgresJdbcUrl.parse("postgres://alice:secret@db.example/simplematch");

    assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:5432/simplematch");
    assertThat(settings.username()).isEqualTo("alice");
    assertThat(settings.password()).isEqualTo("secret");
  }

  @Test
  void preservesJdbcUrlsWithoutInventingCredentials() {
    final PostgresJdbcUrl settings =
        PostgresJdbcUrl.parse("jdbc:postgresql://localhost:5432/simplematch");

    assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/simplematch");
    assertThat(settings.username()).isNull();
    assertThat(settings.password()).isNull();
  }

  @Test
  void preservesPostgresTlsQueryParameters() {
    final PostgresJdbcUrl settings =
        PostgresJdbcUrl.parse(
            "postgresql://alice:secret@db.example:5433/simplematch?sslmode=verify-full&sslrootcert=/etc/tls/ca.crt");

    assertThat(settings.jdbcUrl())
        .isEqualTo(
            "jdbc:postgresql://db.example:5433/simplematch?sslmode=verify-full&sslrootcert=/etc/tls/ca.crt");
  }

  @Test
  void acceptsOnlyVerifiedTlsForSecurePostgresSettings() {
    final PostgresJdbcUrl settings =
        PostgresJdbcUrl.parseSecure(
            "postgresql://db.example:5433/simplematch?sslmode=verify-full&sslrootcert=/etc/tls/ca.crt");

    assertThat(settings.jdbcUrl())
        .isEqualTo(
            "jdbc:postgresql://db.example:5433/simplematch?sslmode=verify-full&sslrootcert=/etc/tls/ca.crt");
  }

  @Test
  void rejectsNonPostgresOrUnverifiedSecureSettings() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                PostgresJdbcUrl.parseSecure(
                    "jdbc:h2:mem:local;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"))
        .withMessageContaining("requires a PostgreSQL JDBC URL");
    assertThatIllegalStateException()
        .isThrownBy(
            () -> PostgresJdbcUrl.parseSecure("jdbc:postgresql://db.example/simplematch"))
        .withMessageContaining("sslmode=verify-full");
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                PostgresJdbcUrl.parseSecure(
                    "jdbc:postgresql://db.example/simplematch?sslmode=require&sslrootcert=/etc/tls/ca.crt"))
        .withMessageContaining("sslmode=verify-full");
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                PostgresJdbcUrl.parseSecure(
                    "jdbc:postgresql://db.example/simplematch?sslmode=verify-full"))
        .withMessageContaining("sslrootcert");
  }

  @Test
  void rejectsDuplicateSecureTlsParameters() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                PostgresJdbcUrl.parseSecure(
                    "jdbc:postgresql://db.example/simplematch?sslmode=verify-full&sslmode=require&sslrootcert=/etc/tls/ca.crt"))
        .withMessageContaining("must not repeat");
  }

  @Test
  void rejectsBlankAndUnsupportedDataSources() {
    assertThatIllegalStateException().isThrownBy(() -> PostgresJdbcUrl.parse(" "));
    assertThatIllegalStateException()
        .isThrownBy(() -> PostgresJdbcUrl.parse("mysql://localhost:3306/simplematch"));
  }

  @Test
  void doesNotExposeCredentialsInInvalidDsnDiagnostics() {
    assertThatIllegalStateException()
        .isThrownBy(() -> PostgresJdbcUrl.parse("mysql://alice:secret@localhost:3306/simplematch"))
        .withMessageContaining("unsupported postgres.dsn scheme")
        .withMessageNotContaining("secret");
  }

  @Test
  void rejectsUnsupportedJdbcDsnSchemes() {
    assertThatIllegalStateException()
        .isThrownBy(() -> PostgresJdbcUrl.parse("jdbc:mysql://localhost:3306/simplematch"))
        .withMessage("unsupported postgres.dsn JDBC scheme");
  }

  @Test
  void rejectsPostgresUrisWithoutHostOrDatabase() {
    assertThatIllegalStateException()
        .isThrownBy(() -> PostgresJdbcUrl.parse("postgresql:///simplematch"));
    assertThatIllegalStateException()
        .isThrownBy(() -> PostgresJdbcUrl.parse("postgresql://localhost"));
  }

}
