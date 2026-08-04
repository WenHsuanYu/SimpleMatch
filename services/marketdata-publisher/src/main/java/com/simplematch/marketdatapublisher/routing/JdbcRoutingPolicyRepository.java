package com.simplematch.marketdatapublisher.routing;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Thin JDBC adapter for Market Reference routing policy rows. */
public final class JdbcRoutingPolicyRepository implements RoutingPolicyRepository {
  private static final RowMapper<RoutingAssignment> ASSIGNMENT_ROW_MAPPER =
      (resultSet, rowNumber) ->
          new RoutingAssignment(
              new com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity(
                  resultSet.getString("symbol"), resultSet.getString("venue_mic")),
              resultSet.getInt("routing_partition"));

  private final JdbcTemplate jdbcTemplate;

  /** Creates the repository with the service-owned schema datasource. */
  public JdbcRoutingPolicyRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public Optional<RoutingPolicy> findById(UUID routingPolicyId) {
    final List<RoutingPolicyRow> rows =
        jdbcTemplate.query(
            """
            SELECT routing_policy_id, source_market_snapshot_id, trading_day,
              effective_from_unix_ms, effective_until_unix_ms,
              orders_validated_partition_count
            FROM marketdata_publisher.routing_policies
            WHERE routing_policy_id = ?
            """,
            (resultSet, rowNumber) ->
                new RoutingPolicyRow(
                    resultSet.getObject("routing_policy_id", UUID.class),
                    resultSet.getObject("source_market_snapshot_id", UUID.class),
                    resultSet.getObject("trading_day", LocalDate.class),
                    resultSet.getLong("effective_from_unix_ms"),
                    resultSet.getLong("effective_until_unix_ms"),
                    resultSet.getInt("orders_validated_partition_count")),
            routingPolicyId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    final RoutingPolicyRow row = rows.get(0);
    final List<RoutingAssignment> assignments =
        jdbcTemplate.query(
            """
            SELECT symbol, venue_mic, routing_partition
            FROM marketdata_publisher.routing_policy_assignments
            WHERE routing_policy_id = ?
            ORDER BY symbol, venue_mic
            """,
            ASSIGNMENT_ROW_MAPPER,
            routingPolicyId);
    return Optional.of(row.toPolicy(assignments));
  }

  @Override
  public Optional<RoutingPolicy> findApplicable(LocalDate tradingDay, Instant at) {
    return findPolicyId(
            """
            SELECT routing_policy_id
            FROM marketdata_publisher.routing_policies
            WHERE trading_day = ? AND active
              AND effective_from_unix_ms <= ? AND effective_until_unix_ms > ?
            ORDER BY effective_from_unix_ms DESC
            LIMIT 1
            """,
            tradingDay,
            at.toEpochMilli(),
            at.toEpochMilli())
        .flatMap(this::findById);
  }

  @Override
  public Optional<RoutingPolicy> findLatestForTradingDay(LocalDate tradingDay) {
    return findPolicyId(
            """
            SELECT routing_policy_id
            FROM marketdata_publisher.routing_policies
            WHERE trading_day = ? AND active
            ORDER BY effective_until_unix_ms DESC, effective_from_unix_ms DESC
            LIMIT 1
            """,
            tradingDay)
        .flatMap(this::findById);
  }

  @Override
  public Optional<RoutingPolicy> findLatestActive() {
    return findPolicyId(
            """
            SELECT routing_policy_id
            FROM marketdata_publisher.routing_policies
            WHERE active
            ORDER BY trading_day DESC, effective_until_unix_ms DESC, effective_from_unix_ms DESC
            LIMIT 1
            """)
        .flatMap(this::findById);
  }

  @Override
  public void lockSourceSnapshot(UUID sourceMarketSnapshotId, LocalDate tradingDay) {
    final List<UUID> matchingSnapshots =
        jdbcTemplate.execute(
            (ConnectionCallback<List<UUID>>)
                connection -> {
                  try (PreparedStatement statement =
                      connection.prepareStatement(
                          """
                          SELECT snapshot_id
                          FROM marketdata_publisher.market_snapshots
                          WHERE snapshot_id = ? AND trading_day = ?
                          FOR UPDATE
                          """)) {
                    statement.setObject(1, sourceMarketSnapshotId);
                    statement.setObject(2, tradingDay);
                    try (ResultSet rows = statement.executeQuery()) {
                      final List<UUID> ids = new java.util.ArrayList<>();
                      while (rows.next()) {
                        ids.add(rows.getObject("snapshot_id", UUID.class));
                      }
                      return ids;
                    }
                  }
                });
    if (matchingSnapshots == null || matchingSnapshots.isEmpty()) {
      throw new RoutingPolicyPublicationConflictException(
          "source market snapshot is not published for the routing policy trading day");
    }
  }

  @Override
  public List<RoutingPolicy> findAllForTradingDayForUpdate(LocalDate tradingDay) {
    final List<UUID> policyIds =
        jdbcTemplate.query(
            """
            SELECT routing_policy_id
            FROM marketdata_publisher.routing_policies
            WHERE trading_day = ? AND active
            ORDER BY effective_from_unix_ms
            FOR UPDATE
            """,
            (resultSet, rowNumber) -> resultSet.getObject("routing_policy_id", UUID.class),
            tradingDay);
    return policyIds.stream()
        .map(this::findById)
        .flatMap(Optional::stream)
        .toList();
  }

  @Override
  public void insert(RoutingPolicy policy, Instant publishedAt) {
    jdbcTemplate.update(
        """
        INSERT INTO marketdata_publisher.routing_policies (
          routing_policy_id, source_market_snapshot_id, trading_day,
          effective_from_unix_ms, effective_until_unix_ms,
          orders_validated_partition_count, active, published_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)
        """,
        policy.identity().routingPolicyId(),
        policy.identity().sourceMarketSnapshotId(),
        policy.identity().tradingDay(),
        policy.effectiveInterval().effectiveFrom().toEpochMilli(),
        policy.effectiveInterval().effectiveUntil().toEpochMilli(),
        policy.ordersValidatedPartitionCount(),
        publishedAt.toEpochMilli());
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO marketdata_publisher.routing_policy_assignments (
          routing_policy_id, symbol, venue_mic, routing_partition
        ) VALUES (?, ?, ?, ?)
        """,
        policy.assignments(),
        policy.assignments().size(),
        (statement, assignment) -> {
          statement.setObject(1, policy.identity().routingPolicyId());
          statement.setString(2, assignment.instrument().symbol());
          statement.setString(3, assignment.instrument().venueMic());
          statement.setInt(4, assignment.routingPartition());
        });
  }

  private Optional<UUID> findPolicyId(String sql, Object... arguments) {
    return jdbcTemplate
        .query(
            sql,
            (resultSet, rowNumber) -> resultSet.getObject("routing_policy_id", UUID.class),
            arguments)
        .stream()
        .findFirst();
  }

  private record RoutingPolicyRow(
      UUID routingPolicyId,
      UUID sourceMarketSnapshotId,
      LocalDate tradingDay,
      long effectiveFromUnixMs,
      long effectiveUntilUnixMs,
      int partitionCount) {
    private RoutingPolicy toPolicy(List<RoutingAssignment> assignments) {
      return new RoutingPolicy(
          new RoutingPolicyIdentity(routingPolicyId, sourceMarketSnapshotId, tradingDay),
          new RoutingPolicyInterval(
              Instant.ofEpochMilli(effectiveFromUnixMs),
              Instant.ofEpochMilli(effectiveUntilUnixMs)),
          partitionCount,
          assignments);
    }
  }
}
