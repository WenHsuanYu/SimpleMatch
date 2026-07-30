package com.simplematch.riskservice.store;

import com.simplematch.riskservice.admission.AdmissionCommand;
import com.simplematch.riskservice.admission.AdmissionJournalEntry;
import com.simplematch.riskservice.admission.AdmissionJournalRepository;
import com.simplematch.riskservice.admission.AdmissionState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Thin JDBC adapter for the risk-owned durable admission journal. */
@Repository
@RequiredArgsConstructor
public class JdbcAdmissionJournalRepository implements AdmissionJournalRepository {
  private static final RowMapper<AdmissionJournalEntry> MAPPER =
      (resultSet, row) ->
          new AdmissionJournalEntry(
              resultSet.getObject("command_id", UUID.class),
              resultSet.getObject("order_id", UUID.class),
              resultSet.getObject("account_id", UUID.class),
              resultSet.getString("symbol"),
              resultSet.getString("venue_mic"),
              resultSet.getString("side"),
              resultSet.getLong("quantity"),
              resultSet.getObject("limit_price_units", Long.class),
              resultSet.getString("order_type"),
              resultSet.getString("tif"),
              resultSet.getObject("trading_day", LocalDate.class),
              resultSet.getString("sender_comp_id"),
              resultSet.getString("target_comp_id"),
              resultSet.getString("cl_ord_id"),
              resultSet.getObject("routing_snapshot_id", UUID.class),
              resultSet.getObject("routing_partition", Integer.class),
              AdmissionState.valueOf(resultSet.getString("state")),
              resultSet.getObject("reservation_id", UUID.class),
              resultSet.getString("reason_code"),
              resultSet.getString("reason_detail"),
              resultSet.getLong("version"),
              resultSet.getLong("created_at_unix_ms"),
              resultSet.getLong("updated_at_unix_ms"));

  private final @NonNull JdbcTemplate jdbcTemplate;

  @Override
  public Optional<AdmissionJournalEntry> findByCommandId(UUID commandId) {
    return jdbcTemplate.query(select() + " WHERE command_id = ?", MAPPER, commandId).stream()
        .findFirst();
  }

  @Override
  public Optional<AdmissionJournalEntry> findByBusinessKey(AdmissionCommand command) {
    return jdbcTemplate
        .query(
            select()
                + " WHERE sender_comp_id = ? AND target_comp_id = ?"
                + " AND trading_day = ? AND cl_ord_id = ?",
            MAPPER,
            command.fixIdentity().senderCompId().value(),
            command.fixIdentity().targetCompId().value(),
            command.order().tradingDay(),
            command.fixIdentity().clOrdId().value())
        .stream()
        .findFirst();
  }

  @Override
  public boolean insert(AdmissionJournalEntry entry) {
    final String suffix = isPostgres() ? " ON CONFLICT DO NOTHING" : "";
    return jdbcTemplate.update(
            """
            INSERT INTO risk_service.admission_journal (
              command_id, order_id, account_id, symbol, venue_mic, side, quantity,
              limit_price_units, order_type, tif, trading_day, sender_comp_id,
              target_comp_id, cl_ord_id, routing_snapshot_id, routing_partition, state,
              reservation_id, reason_code, reason_detail, version,
              created_at_unix_ms, updated_at_unix_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                + suffix,
            entry.commandId(),
            entry.orderId(),
            entry.accountId(),
            entry.symbol(),
            entry.venueMic(),
            entry.side(),
            entry.quantity(),
            entry.limitPriceUnits(),
            entry.orderType(),
            entry.tif(),
            entry.tradingDay(),
            entry.senderCompId(),
            entry.targetCompId(),
            entry.clOrdId(),
            entry.routingSnapshotId(),
            entry.routingPartition(),
            entry.state().name(),
            entry.reservationId(),
            entry.reasonCode(),
            entry.reasonDetail(),
            entry.version(),
            entry.createdAtUnixMs(),
            entry.updatedAtUnixMs())
        == 1;
  }

  @Override
  public void update(AdmissionJournalEntry entry, long expectedVersion) {
    final int updated =
        jdbcTemplate.update(
            "UPDATE risk_service.admission_journal SET state = ?, reservation_id = ?, "
                + "routing_partition = ?, reason_code = ?, reason_detail = ?, version = ?, "
                + "updated_at_unix_ms = ? WHERE command_id = ? AND version = ?",
            entry.state().name(),
            entry.reservationId(),
            entry.routingPartition(),
            entry.reasonCode(),
            entry.reasonDetail(),
            entry.version(),
            entry.updatedAtUnixMs(),
            entry.commandId(),
            expectedVersion);
    if (updated != 1) {
      throw new IllegalStateException("admission journal optimistic version conflict");
    }
  }

  @Override
  public List<AdmissionJournalEntry> findPendingBefore(Instant cutoff, int limit) {
    return jdbcTemplate.query(
        select()
            + " WHERE state = 'PENDING' AND updated_at_unix_ms < ?"
            + " ORDER BY updated_at_unix_ms LIMIT ?",
        MAPPER,
        cutoff.toEpochMilli(),
        limit);
  }

  private String select() {
    return
        """
        SELECT command_id, order_id, account_id, symbol, venue_mic, side, quantity,
          limit_price_units, order_type, tif, trading_day, sender_comp_id, target_comp_id,
          cl_ord_id, routing_snapshot_id, routing_partition, state, reservation_id,
          reason_code, reason_detail, version, created_at_unix_ms, updated_at_unix_ms
        FROM risk_service.admission_journal
        """;
  }

  private boolean isPostgres() {
    return Objects.requireNonNull(
        jdbcTemplate.execute(
            (ConnectionCallback<Boolean>)
                connection ->
                    connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")),
        "database product name");
  }
}
