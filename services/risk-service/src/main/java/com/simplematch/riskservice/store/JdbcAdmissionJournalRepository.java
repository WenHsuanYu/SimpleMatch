package com.simplematch.riskservice.store;

import com.simplematch.riskservice.admission.AdmissionCommand;
import com.simplematch.riskservice.admission.AdmissionDecision;
import com.simplematch.riskservice.admission.AdmissionFixIdentity;
import com.simplematch.riskservice.admission.AdmissionIdentity;
import com.simplematch.riskservice.admission.AdmissionJournalEntry;
import com.simplematch.riskservice.admission.AdmissionJournalRepository;
import com.simplematch.riskservice.admission.AdmissionOrder;
import java.time.Instant;
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
  private static final RowMapper<AdmissionJournalEntry> MAPPER = AdmissionJournalRowMapper.MAPPER;

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
    final AdmissionCommand command = entry.command();
    final AdmissionIdentity identity = command.identity();
    final AdmissionOrder order = command.order();
    final AdmissionOrder.Instrument instrument = order.instrument();
    final AdmissionOrder.Characteristics characteristics = order.characteristics();
    final AdmissionFixIdentity fixIdentity = command.fixIdentity();
    final AdmissionDecision decision = entry.lifecycle().decision();
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
            identity.commandId().value(),
            identity.orderId().value(),
            identity.accountId().value(),
            instrument.symbol().value(),
            instrument.venueMic().value(),
            characteristics.side().value(),
            characteristics.quantity().value(),
            characteristics.limitPrice().value(),
            characteristics.orderType().value(),
            characteristics.timeInForce().value(),
            order.tradingDay(),
            fixIdentity.senderCompId().value(),
            fixIdentity.targetCompId().value(),
            fixIdentity.clOrdId().value(),
            command.routing().snapshotId().value(),
            entry.route().routingPartition(),
            decision.state().name(),
            decision.reservationId(),
            decision.reasonCode(),
            decision.reasonDetail(),
            entry.lifecycle().version(),
            entry.lifecycle().createdAtUnixMs(),
            entry.lifecycle().updatedAtUnixMs())
        == 1;
  }

  @Override
  public void update(AdmissionJournalEntry entry, long expectedVersion) {
    final AdmissionDecision decision = entry.lifecycle().decision();
    final int updated =
        jdbcTemplate.update(
            "UPDATE risk_service.admission_journal SET state = ?, reservation_id = ?, "
                + "routing_partition = ?, reason_code = ?, reason_detail = ?, version = ?, "
                + "updated_at_unix_ms = ? WHERE command_id = ? AND version = ?",
            decision.state().name(),
            decision.reservationId(),
            entry.route().routingPartition(),
            decision.reasonCode(),
            decision.reasonDetail(),
            entry.lifecycle().version(),
            entry.lifecycle().updatedAtUnixMs(),
            entry.command().identity().commandId().value(),
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
