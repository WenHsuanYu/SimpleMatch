package com.simplematch.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.matching.AccountFinalMatchingEventStatus;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountHandler;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountOutcome;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Verifies restart restoration of Account's durable critical-consumer state. */
class AccountFinalMatchingEventProgressRestartTest {
  @Test
  void restartRestoresNextKafkaPositionFromDurableProgress() {
    final SingleConnectionDataSource dataSource = newDataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        """
        INSERT INTO account_service.matching_event_consumer_progress (
          consumer_name, partition_id, last_processed_offset, updated_at_unix_ms
        ) VALUES ('account-final-matching-events', 0, 42, 1000)
        """);

    contextRunner(jdbcTemplate)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(AccountFinalMatchingEventStatus.class)
                          .committedOffsets())
                  .containsEntry(0, 43L);
            });
  }

  @Test
  void restartRestoresOpenQuarantine() {
    final SingleConnectionDataSource dataSource = newDataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        """
        INSERT INTO account_service.matching_event_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, payload_sha256,
          reason, status, quarantined_at_unix_ms
        ) VALUES (?, ?, ?, ?, ?, ?, 'QUARANTINED', ?)
        """,
        "account-final-matching-events",
        "matching.events",
        0,
        42L,
        new byte[32],
        "fixture failure",
        1000L);

    contextRunner(jdbcTemplate)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              final DeliveryPosition blocked =
                  new DeliveryPosition("matching.events", 0, 42L);
              final AccountFinalMatchingEventStatus status =
                  context.getBean(AccountFinalMatchingEventStatus.class);
              final CriticalDeliveryController controller =
                  context.getBean(CriticalDeliveryController.class);

              assertThat(status.state())
                  .isEqualTo(AccountFinalMatchingEventStatus.State.QUARANTINED);
              assertThat(status.quarantinePosition()).contains(blocked);
              assertThat(controller.isPaused(blocked.topicPartition())).isTrue();
              assertThat(controller.pausedOffset(blocked.topicPartition())).hasValue(42L);
            });
  }

  private ApplicationContextRunner contextRunner(JdbcTemplate jdbcTemplate) {
    return new ApplicationContextRunner()
        .withUserConfiguration(AccountFinalMatchingEventConsumerConfiguration.class)
        .withPropertyValues("simplematch.account-service.final-matching-events.enabled=true")
        .withBean(JdbcTemplate.class, () -> jdbcTemplate)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(
            JdbcFinalMatchingEventAccountInbox.class,
            () -> new JdbcFinalMatchingEventAccountInbox(jdbcTemplate))
        .withBean(
            FinalMatchingEventAccountHandler.class,
            () ->
                (command, partition, offset) -> FinalMatchingEventAccountOutcome.APPLIED);
  }

  private SingleConnectionDataSource newDataSource() {
    final SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:account_progress_restart_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    dataSource.setDriverClassName("org.h2.Driver");
    return dataSource;
  }
}
