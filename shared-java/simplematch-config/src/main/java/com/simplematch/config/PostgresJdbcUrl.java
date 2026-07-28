package com.simplematch.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Parsed PostgreSQL connection settings derived from one configured JDBC URL or PostgreSQL URI.
 *
 * <p>This centralizes URI normalization so service datasource modules do not each own a subtly
 * different connection-string parser.
 *
 * @param jdbcUrl  normalized JDBC URL
 * @param username optional URI user name
 * @param password optional URI password
 */
public record PostgresJdbcUrl(String jdbcUrl, String username, String password) {
    /**
     * Converts a JDBC URL or PostgreSQL URI to JDBC connection settings.
     *
     * @param rawDsn configured JDBC URL or PostgreSQL URI
     * @return normalized JDBC settings
     * @throws IllegalStateException if the DSN is blank, malformed, or not PostgreSQL
     */
    public static PostgresJdbcUrl parse(String rawDsn) {
        requireDsn(rawDsn);
        if (rawDsn.startsWith("jdbc:")) {
            return new PostgresJdbcUrl(rawDsn, null, null);
        }
        try {
            return parseUri(new URI(rawDsn), rawDsn);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("failed to parse postgres.dsn: " + rawDsn, exception);
        }
    }

    private static void requireDsn(String rawDsn) {
        if (rawDsn == null || rawDsn.isBlank()) {
            throw new IllegalStateException("postgres.dsn must not be blank");
        }
    }

    private static PostgresJdbcUrl parseUri(URI uri, String rawDsn) {
        if (!isPostgresScheme(uri)) {
            throw new IllegalStateException("unsupported postgres.dsn scheme: " + rawDsn);
        }
        if (uri.getHost() == null || uri.getPath() == null || uri.getPath().isBlank()) {
            throw new IllegalStateException("postgres.dsn must identify a host and database");
        }
        final Credentials credentials = credentials(uri);
        return new PostgresJdbcUrl(jdbcUrl(uri), credentials.username(), credentials.password());
    }

    private static boolean isPostgresScheme(URI uri) {
        return "postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme());
    }

    private static Credentials credentials(URI uri) {
        final String userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            return new Credentials(null, null);
        }
        final String[] parts = userInfo.split(":", 2);
        if (parts.length == 1) {
            return new Credentials(parts[0], null);
        }
        return new Credentials(parts[0], parts[1]);
    }

    private static String jdbcUrl(URI uri) {
        final int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
    }

    private record Credentials(String username, String password) {
    }
}
