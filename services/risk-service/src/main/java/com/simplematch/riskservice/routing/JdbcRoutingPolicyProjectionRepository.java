package com.simplematch.riskservice.routing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Thin JDBC adapter for the Risk-owned routing-policy projection tables. */
public final class JdbcRoutingPolicyProjectionRepository
    implements RoutingPolicyProjectionRepository {
  private static final RowMapper<RoutingPolicyAssignment> ASSIGNMENT_ROW_MAPPER =
      (resultSet, rowNumber) ->
          new RoutingPolicyAssignment(
              new RoutingInstrument(
                  resultSet.getString("symbol"), resultSet.getString("venue_mic")),
              resultSet.getInt("routing_partition"));

  private final JdbcTemplate jdbcTemplate;

  /** Creates the repository with Risk's service-local datasource. */
  public JdbcRoutingPolicyProjectionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public Optional<RoutingPolicyProjection> findById(UUID routingPolicyId) {
    final List<PolicyRow> rows =
        jdbcTemplate.query(
            """
            SELECT routing_policy_id, source_market_snapshot_id, trading_day,
              effective_from_unix_ms, effective_until_unix_ms,
              partition_count
            FROM risk_service.routing_policies
            WHERE routing_policy_id = ? AND active
            """,
            (resultSet, rowNumber) ->
                new PolicyRow(
                    resultSet.getObject("routing_policy_id", UUID.class),
                    resultSet.getObject("source_market_snapshot_id", UUID.class),
                    resultSet.getObject("trading_day", LocalDate.class),
                    resultSet.getLong("effective_from_unix_ms"),
                    resultSet.getLong("effective_until_unix_ms"),
                    resultSet.getInt("partition_count")),
            routingPolicyId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    final PolicyRow row = rows.get(0);
    return Optional.of(row.toProjection(assignments(routingPolicyId)));
  }

  @Override
  public Optional<RoutingPolicyProjection> findApplicable(LocalDate tradingDay, Instant at) {
    return findPolicyId(
            """
            SELECT routing_policy_id
            FROM risk_service.routing_policies
            WHERE active AND trading_day = ?
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
  public Optional<RoutingPolicyProjection> findLatestActive() {
    return findPolicyId(
            """
            SELECT routing_policy_id
            FROM risk_service.routing_policies
            WHERE active
            ORDER BY trading_day DESC, effective_until_unix_ms DESC
            LIMIT 1
            """)
        .flatMap(this::findById);
  }

  @Override
  public void insertStaged(RoutingPolicyProjection projection, Instant receivedAt) {
    jdbcTemplate.update(
        """
        INSERT INTO risk_service.routing_policies (
          routing_policy_id, source_market_snapshot_id, trading_day,
          effective_from_unix_ms, effective_until_unix_ms,
          partition_count, active, received_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)
        """,
        projection.identity().routingPolicyId(),
        projection.identity().sourceMarketSnapshotId(),
        projection.identity().tradingDay(),
        projection.effectiveInterval().effectiveFrom().toEpochMilli(),
        projection.effectiveInterval().effectiveUntil().toEpochMilli(),
        projection.topology().partitionCount(),
        receivedAt.toEpochMilli());
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO risk_service.routing_policy_assignments (
          routing_policy_id, symbol, venue_mic, routing_partition
        ) VALUES (?, ?, ?, ?)
        """,
        projection.assignments(),
        projection.assignments().size(),
        (statement, assignment) -> {
          statement.setObject(1, projection.identity().routingPolicyId());
          statement.setString(2, assignment.instrument().symbol());
          statement.setString(3, assignment.instrument().venueMic());
          statement.setInt(4, assignment.routingPartition());
        });
  }

  @Override
  public void activate(UUID routingPolicyId) {
    if (jdbcTemplate.update(
            "UPDATE risk_service.routing_policies SET active = TRUE WHERE routing_policy_id = ?",
            routingPolicyId)
        != 1) {
      throw new IllegalStateException("routing policy projection activation found no staged row");
    }
  }

  private List<RoutingPolicyAssignment> assignments(UUID routingPolicyId) {
    return jdbcTemplate.query(
        """
        SELECT symbol, venue_mic, routing_partition
        FROM risk_service.routing_policy_assignments
        WHERE routing_policy_id = ?
        ORDER BY symbol, venue_mic
        """,
        ASSIGNMENT_ROW_MAPPER,
        routingPolicyId);
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

  private record PolicyRow(
      UUID routingPolicyId,
      UUID sourceMarketSnapshotId,
      LocalDate tradingDay,
      long effectiveFromUnixMs,
      long effectiveUntilUnixMs,
      int partitionCount) {
    private RoutingPolicyProjection toProjection(List<RoutingPolicyAssignment> assignments) {
      return new RoutingPolicyProjection(
          new RoutingPolicyProjectionIdentity(
              routingPolicyId, sourceMarketSnapshotId, tradingDay),
          new RoutingPolicyProjectionInterval(
              Instant.ofEpochMilli(effectiveFromUnixMs),
              Instant.ofEpochMilli(effectiveUntilUnixMs)),
          new RoutingPolicyPartitionTopology(partitionCount),
          assignments);
    }
  }
}
