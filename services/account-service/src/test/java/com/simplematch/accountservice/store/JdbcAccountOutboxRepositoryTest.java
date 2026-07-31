package com.simplematch.accountservice.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.accountservice.authority.AccountLifecycleOutbox;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcAccountOutboxRepositoryTest {
  @DisplayName("JDBC adapter flattens semantic outbox groups into the existing row shape")
  @Test
  void mapsSemanticOutboxToExistingColumns() {
    final DriverManagerDataSource dataSource = dataSource();
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    final UUID eventId = UUID.randomUUID();
    final AccountLifecycleOutbox event =
        new AccountLifecycleOutbox(
            new AccountLifecycleOutbox.EventIdentity(eventId),
            new AccountLifecycleOutbox.Destination("account.lifecycle", "order-1"),
            new AccountLifecycleOutbox.Payload(new byte[] {1, 2}, "event.v1", "{}"),
            new AccountLifecycleOutbox.AggregateReference("reservation", "reservation-1"),
            100L);

    new JdbcAccountOutboxRepository(jdbcTemplate).insert(event);

    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT event_id, topic, message_key, payload, payload_type, headers_json, "
                    + "aggregate_type, aggregate_id, created_at_unix_ms "
                    + "FROM account_service.outbox"))
        .containsEntry("EVENT_ID", eventId)
        .containsEntry("TOPIC", "account.lifecycle")
        .containsEntry("MESSAGE_KEY", "order-1")
        .containsEntry("PAYLOAD_TYPE", "event.v1")
        .containsEntry("HEADERS_JSON", "{}")
        .containsEntry("AGGREGATE_TYPE", "reservation")
        .containsEntry("AGGREGATE_ID", "reservation-1")
        .containsEntry("CREATED_AT_UNIX_MS", 100L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payload FROM account_service.outbox", byte[].class))
        .containsExactly(1, 2);
  }

  private DriverManagerDataSource dataSource() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:account-outbox-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS account_service\\;SET SCHEMA account_service");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
    return dataSource;
  }
}
