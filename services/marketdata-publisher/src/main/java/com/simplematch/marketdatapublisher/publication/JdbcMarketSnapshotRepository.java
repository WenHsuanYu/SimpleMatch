package com.simplematch.marketdatapublisher.publication;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Thin JDBC adapter for market-reference snapshot rows. */
public final class JdbcMarketSnapshotRepository implements MarketSnapshotRepository {
  private static final int ACTIVE_SNAPSHOT_LOCK_TIMEOUT_SECONDS = 2;
  private static final RowMapper<PublishedMarketSnapshot> ROW_MAPPER = (resultSet, rowNumber) ->
      new PublishedMarketSnapshot(
          resultSet.getObject("snapshot_id", UUID.class),
          resultSet.getObject("trading_day", LocalDate.class),
          resultSet.getLong("version"),
          resultSet.getString("source_identity"),
          resultSet.getLong("source_timestamp_unix_ms"),
          resultSet.getString("checksum"),
          new String(resultSet.getBytes("snapshot_payload"), StandardCharsets.UTF_8),
          resultSet.getBoolean("active"),
          Instant.ofEpochMilli(resultSet.getLong("published_at_unix_ms")));

  private final JdbcTemplate jdbcTemplate;

  /** Creates the repository with the service-owned schema datasource. */
  public JdbcMarketSnapshotRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public Optional<PublishedMarketSnapshot> findBySourceIdentityAndChecksum(
      String sourceIdentity, String checksum) {
    return jdbcTemplate.query(
        """
        SELECT snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
          snapshot_payload, active, published_at_unix_ms
        FROM marketdata_publisher.market_snapshots
        WHERE source_identity = ? AND checksum = ?
        """,
        ROW_MAPPER,
        sourceIdentity,
        checksum).stream().findFirst();
  }

  @Override
  public Optional<PublishedMarketSnapshot> findActive(LocalDate tradingDay) {
    return jdbcTemplate.query(
        """
        SELECT snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
          snapshot_payload, active, published_at_unix_ms
        FROM marketdata_publisher.market_snapshots
        WHERE trading_day = ? AND active
        """,
        ROW_MAPPER,
        tradingDay).stream().findFirst();
  }

  @Override
  public Optional<PublishedMarketSnapshot> findLatestActive() {
    return jdbcTemplate.query(
        """
        SELECT snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
          snapshot_payload, active, published_at_unix_ms
        FROM marketdata_publisher.market_snapshots
        WHERE active
        ORDER BY trading_day DESC
        LIMIT 1
        """,
        ROW_MAPPER).stream().findFirst();
  }

  @Override
  public Optional<PublishedMarketSnapshot> findActiveForUpdate(LocalDate tradingDay) {
    return jdbcTemplate.execute((ConnectionCallback<Optional<PublishedMarketSnapshot>>) connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
            snapshot_payload, active, published_at_unix_ms
          FROM marketdata_publisher.market_snapshots
          WHERE trading_day = ? AND active
          FOR UPDATE
          """)) {
        statement.setObject(1, tradingDay);
        statement.setQueryTimeout(ACTIVE_SNAPSHOT_LOCK_TIMEOUT_SECONDS);
        try (ResultSet rows = statement.executeQuery()) {
          return rows.next() ? Optional.of(ROW_MAPPER.mapRow(rows, 0)) : Optional.empty();
        }
      }
    });
  }

  @Override
  public long nextVersion(LocalDate tradingDay) {
    return Objects.requireNonNull(jdbcTemplate.queryForObject(
        "SELECT COALESCE(MAX(version), 0) + 1 FROM marketdata_publisher.market_snapshots WHERE trading_day = ?",
        Long.class,
        tradingDay), "next snapshot version query returned no value");
  }

  @Override
  public void deactivateActive(LocalDate tradingDay) {
    jdbcTemplate.update(
        """
        UPDATE marketdata_publisher.market_snapshots
        SET active = FALSE, active_trading_day = NULL
        WHERE trading_day = ? AND active
        """,
        tradingDay);
  }

  @Override
  public void insert(PublishedMarketSnapshot snapshot) {
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.market_snapshots (
          snapshot_id, trading_day, version, source_identity, source_timestamp_unix_ms, checksum,
          snapshot_payload, active, active_trading_day, published_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        snapshot.snapshotId(),
        snapshot.tradingDay(),
        snapshot.version(),
        snapshot.sourceIdentity(),
        snapshot.sourceTimestampUnixMs(),
        snapshot.checksum(),
        snapshot.canonicalContent().getBytes(StandardCharsets.UTF_8),
        snapshot.active(),
        snapshot.active() ? snapshot.tradingDay() : null,
        snapshot.publishedAt().toEpochMilli());
  }
}
