package com.simplematch.riskservice.store;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.uniqueRiskServiceUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.riskservice.cdc.CdcDeliveryObservation;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcCdcDeliveryProgressStoreTest {
  private static final UUID DELIVERED =
      UUID.fromString("01990f4a-ff80-7c2c-b71c-33caa9b271d2");
  private static final UUID PENDING =
      UUID.fromString("01990f4a-ff80-7c2c-b71c-33caa9b271d3");

  private JdbcTemplate jdbc;
  private JdbcCdcDeliveryProgressStore store;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(uniqueRiskServiceUrl(), "sa", "");
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE TABLE risk_service.outbox (event_id UUID PRIMARY KEY, topic VARCHAR(255) NOT NULL, created_at_unix_ms BIGINT NOT NULL)");
    jdbc.execute("CREATE TABLE risk_service.cdc_delivery_observation (event_id UUID PRIMARY KEY, topic VARCHAR(255) NOT NULL, partition_id INTEGER NOT NULL, kafka_offset BIGINT NOT NULL, observed_at_unix_ms BIGINT NOT NULL)");
    jdbc.execute("CREATE TABLE risk_service.cdc_delivery_lag (metric_name VARCHAR(128) PRIMARY KEY, lag_events BIGINT NOT NULL, updated_at_unix_ms BIGINT NOT NULL)");
    jdbc.update("INSERT INTO risk_service.cdc_delivery_lag VALUES (?, 0, 0)", "matching.commands");
    store = new JdbcCdcDeliveryProgressStore(jdbc);
  }

  @Test
  @DisplayName("derives lag from Risk outbox rows missing an exact Kafka observation")
  void derivesLagFromRiskOutboxRowsMissingAnExactKafkaObservation() {
    insertOutbox(DELIVERED, 1_000L);
    insertOutbox(PENDING, 2_000L);
    store.observe(
        new CdcDeliveryObservation(
            DELIVERED, new DeliveryPosition("matching.commands", 2, 14), 3_000));

    final var snapshot = store.refresh("matching.commands", "matching.commands", 5_000L);

    assertThat(snapshot.lagEvents()).isEqualTo(1);
    assertThat(snapshot.oldestUndeliveredAgeMillis()).isEqualTo(3_000);
    assertThat(jdbc.queryForObject(
            "SELECT updated_at_unix_ms FROM risk_service.cdc_delivery_lag WHERE metric_name = ?",
            Long.class,
            "matching.commands"))
        .isEqualTo(5_000L);
  }

  @Test
  @DisplayName("returns to zero after the pending outbox event is observed")
  void returnsToZeroAfterThePendingOutboxEventIsObserved() {
    insertOutbox(PENDING, 2_000L);
    assertThat(store.refresh("matching.commands", "matching.commands", 5_000L).lagEvents())
        .isEqualTo(1);

    store.observe(
        new CdcDeliveryObservation(
            PENDING, new DeliveryPosition("matching.commands", 4, 18), 5_500));

    final var recovered = store.refresh("matching.commands", "matching.commands", 6_000L);
    assertThat(recovered.lagEvents()).isZero();
    assertThat(recovered.oldestUndeliveredAgeMillis()).isZero();
  }

  @Test
  @DisplayName("rejects refresh when the configured metric row is missing")
  void rejectsRefreshWhenTheConfiguredMetricRowIsMissing() {
    jdbc.update("DELETE FROM risk_service.cdc_delivery_lag WHERE metric_name = ?", "matching.commands");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> store.refresh("matching.commands", "matching.commands", 5_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("matching.commands");
  }

  @Test
  @DisplayName("ignores Kafka records that do not belong to the Risk outbox")
  void ignoresKafkaRecordsThatDoNotBelongToTheRiskOutbox() {
    store.observe(
        new CdcDeliveryObservation(
            UUID.fromString("01990f4a-ff80-7c2c-b71c-33caa9b271d4"),
            new DeliveryPosition("matching.commands", 1, 9),
            3_000));

    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM risk_service.cdc_delivery_observation", Long.class))
        .isZero();
  }

  @Test
  @DisplayName("deduplicates repeated Kafka observations by event identity")
  void deduplicatesRepeatedKafkaObservationsByEventIdentity() {
    insertOutbox(DELIVERED, 1_000L);
    final CdcDeliveryObservation observation =
        new CdcDeliveryObservation(
            DELIVERED, new DeliveryPosition("matching.commands", 2, 14), 3_000);

    store.observe(observation);
    store.observe(observation);

    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM risk_service.cdc_delivery_observation WHERE event_id = ?",
            Long.class,
            DELIVERED))
        .isEqualTo(1);
  }

  private void insertOutbox(UUID eventId, long createdAtUnixMs) {
    jdbc.update(
        "INSERT INTO risk_service.outbox (event_id, topic, created_at_unix_ms) VALUES (?, ?, ?)",
        eventId,
        "matching.commands",
        createdAtUnixMs);
  }
}
