package com.simplematch.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.persistence.kafka.PersistenceMatchingEventStatus;
import com.simplematch.persistence.matching.MatchingEventPersistenceHandler;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import com.simplematch.persistence.store.JdbcMatchingEventStore;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Verifies restart restoration of Persistence's durable critical-consumer state. */
class PersistenceMatchingEventProgressRestartTest {
  @Test
  void restartRestoresNextKafkaPositionFromDurableProgress() {
    final SingleConnectionDataSource dataSource = newDataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/persistence")
        .load()
        .migrate();
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        """
        INSERT INTO persistence.matching_consumer_progress (
          consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
        ) VALUES ('persistence-matching-events', 3, 42, 1000)
        """);

    contextRunner(jdbcTemplate)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(PersistenceMatchingEventStatus.class).committedOffsets())
                  .containsEntry(3, 43L);
            });
  }

  @Test
  void restartRestoresOpenQuarantine() {
    final SingleConnectionDataSource dataSource = newDataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/persistence")
        .load()
        .migrate();
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        """
        INSERT INTO persistence.matching_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, payload_sha256,
          reason, status, quarantined_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, 'QUARANTINED', ?)
        """,
        "persistence-matching-events",
        "matching.events",
        3,
        42L,
        new byte[32],
        "fixture failure",
        1000L);

    contextRunner(jdbcTemplate)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final DeliveryPosition blocked =
                  new DeliveryPosition("matching.events", 3, 42L);
              final PersistenceMatchingEventStatus status =
                  context.getBean(PersistenceMatchingEventStatus.class);
              final CriticalDeliveryController controller =
                  context.getBean(CriticalDeliveryController.class);

              assertThat(status.state())
                  .isEqualTo(PersistenceMatchingEventStatus.State.QUARANTINED);
              assertThat(status.quarantinePosition()).contains(blocked);
              assertThat(controller.isPaused(blocked.topicPartition())).isTrue();
              assertThat(controller.pausedOffset(blocked.topicPartition())).hasValue(42L);
            });
  }

  private ApplicationContextRunner contextRunner(JdbcTemplate jdbcTemplate) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
        .withUserConfiguration(PersistenceMatchingEventConsumerConfiguration.class)
        .withPropertyValues(
            "simplematch.persistence.matching-events.enabled=true",
            "spring.kafka.bootstrap-servers=localhost:65535",
            "spring.kafka.consumer.group-id=persistence-matching-events")
        .withBean(JdbcTemplate.class, () -> jdbcTemplate)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(JdbcMatchingEventStore.class, () -> new JdbcMatchingEventStore(jdbcTemplate))
        .withBean(
            MatchingEventPersistenceHandler.class,
            () ->
                (envelope, partition, offset) -> MatchingEventPersistenceOutcome.APPLIED);
  }

  private SingleConnectionDataSource newDataSource() {
    final SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:persistence_progress_restart_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    dataSource.setDriverClassName("org.h2.Driver");
    return dataSource;
  }
}
