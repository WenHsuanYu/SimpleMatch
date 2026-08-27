package com.simplematch.quickfixgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.quickfixgateway.fix.FixSessionMessageSender;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventStatus;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Verifies restart restoration of the Gateway's durable final-event consumer state. */
class QuickFixFinalMatchingEventProgressRestartTest {
  private SingleConnectionDataSource dataSource;

  @AfterEach
  void tearDown() {
    if (dataSource != null) {
      dataSource.destroy();
    }
  }

  @Test
  void restartRestoresNextKafkaPositionFromDurableProgress() {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.matching_consumer_progress (
          consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
        ) VALUES ('quickfix-final-matching-events', 2, 42, 1000)
        """);

    contextRunner(jdbcTemplate)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(QuickFixFinalMatchingEventStatus.class)
                          .committedOffsets())
                  .containsEntry(2, 43L);
            });
  }

  @Test
  void restartRestoresOpenQuarantine() {
    final JdbcTemplate jdbcTemplate = migratedDatabase();
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.matching_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, payload_sha256,
          reason, status, quarantined_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, 'QUARANTINED', ?)
        """,
        "quickfix-final-matching-events",
        "matching.events",
        2,
        42L,
        new byte[32],
        "fixture failure",
        1000L);

    contextRunner(jdbcTemplate)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final DeliveryPosition blocked =
                  new DeliveryPosition("matching.events", 2, 42L);
              final QuickFixFinalMatchingEventStatus status =
                  context.getBean(QuickFixFinalMatchingEventStatus.class);
              final CriticalDeliveryController controller =
                  context.getBean(CriticalDeliveryController.class);

              assertThat(status.state())
                  .isEqualTo(QuickFixFinalMatchingEventStatus.State.QUARANTINED);
              assertThat(status.quarantinePosition()).contains(blocked);
              assertThat(controller.isPaused(blocked.topicPartition())).isTrue();
              assertThat(controller.pausedOffset(blocked.topicPartition())).hasValue(42L);
            });
  }

  private JdbcTemplate migratedDatabase() {
    dataSource = newDataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/quickfix-gateway")
        .load()
        .migrate();
    return new JdbcTemplate(dataSource);
  }

  private ApplicationContextRunner contextRunner(JdbcTemplate jdbcTemplate) {
    return new ApplicationContextRunner()
        .withUserConfiguration(QuickFixGatewayFinalMatchingEventConfiguration.class)
        .withPropertyValues(
            "simplematch.quickfix-gateway.data-plane-enabled=true",
            "simplematch.quickfix-gateway.final-matching-events.enabled=true")
        .withBean(JdbcTemplate.class, () -> jdbcTemplate)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(OrderSessionRegistry.class, OrderSessionRegistry::new)
        .withBean(FixSessionMessageSender.class, () -> (sessionId, message) -> {});
  }

  private SingleConnectionDataSource newDataSource() {
    final SingleConnectionDataSource singleConnectionDataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:quickfix_progress_restart_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    singleConnectionDataSource.setDriverClassName("org.h2.Driver");
    return singleConnectionDataSource;
  }
}
