package com.simplematch.riskservice.store;

import com.simplematch.riskservice.cdc.CdcDeliveryObservation;
import com.simplematch.riskservice.cdc.CdcDeliveryProgressStore;
import com.simplematch.riskservice.cdc.CdcDeliverySnapshot;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC persistence adapter for Risk-owned CDC delivery evidence. */
public final class JdbcCdcDeliveryProgressStore implements CdcDeliveryProgressStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  /** Creates the adapter with the Risk datasource and transaction boundary. */
  public JdbcCdcDeliveryProgressStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public void observe(CdcDeliveryObservation observation) {
    Objects.requireNonNull(observation, "observation");
    jdbc.update(
        """
        INSERT INTO risk_service.cdc_delivery_observation
          (event_id, topic, partition_id, kafka_offset, observed_at_unix_ms)
        SELECT event_id, ?, ?, ?, ?
        FROM risk_service.outbox
        WHERE event_id = ? AND topic = ?
          AND NOT EXISTS (
            SELECT 1 FROM risk_service.cdc_delivery_observation WHERE event_id = ?)
        """,
        observation.topic(),
        observation.partition(),
        observation.offset(),
        observation.observedAtUnixMs(),
        observation.eventId(),
        observation.topic(),
        observation.eventId());
  }

  @Override
  public CdcDeliverySnapshot refresh(String metricName, String topic, long measuredAtUnixMs) {
    requireText(metricName, "metricName");
    requireText(topic, "topic");
    if (measuredAtUnixMs < 0) {
      throw new IllegalArgumentException("measuredAtUnixMs must not be negative");
    }
    final CdcDeliverySnapshot snapshot =
        transactions.execute(
            status -> {
              final CdcDeliverySnapshot measured = measureBacklog(topic, measuredAtUnixMs);
              final int updatedRows =
                  jdbc.update(
                      """
                      UPDATE risk_service.cdc_delivery_lag
                      SET lag_events = ?, updated_at_unix_ms = ?
                      WHERE metric_name = ?
                      """,
                      measured.lagEvents(),
                      measuredAtUnixMs,
                      metricName);
              if (updatedRows != 1) {
                throw new IllegalStateException(
                    "CDC delivery metric row is missing: " + metricName);
              }
              return measured;
            });
    if (snapshot == null) {
      throw new IllegalStateException("CDC delivery transaction returned no result");
    }
    return snapshot;
  }

  private CdcDeliverySnapshot measureBacklog(String topic, long measuredAtUnixMs) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*) AS lag_events, MIN(o.created_at_unix_ms) AS oldest_created_at
        FROM risk_service.outbox o
        LEFT JOIN risk_service.cdc_delivery_observation d ON d.event_id = o.event_id
        WHERE o.topic = ? AND d.event_id IS NULL
        """,
        (resultSet, rowNumber) -> {
          final long lagEvents = resultSet.getLong("lag_events");
          final Long oldestCreatedAt = resultSet.getObject("oldest_created_at", Long.class);
          final long oldestAge =
              oldestCreatedAt == null ? 0 : Math.max(0, measuredAtUnixMs - oldestCreatedAt);
          return new CdcDeliverySnapshot(lagEvents, oldestAge);
        },
        topic);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
