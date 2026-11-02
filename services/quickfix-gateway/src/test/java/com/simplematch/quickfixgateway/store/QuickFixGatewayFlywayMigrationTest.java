package com.simplematch.quickfixgateway.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.config.testsupport.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies a clean Gateway database receives its durable final-event and QuickFIX/J schema. */
class QuickFixGatewayFlywayMigrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"event_log", "messages_log"})
  void logRowsAcceptIdsBeyondIntegerRange(String tableName) {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());

    jdbcTemplate.update(
        "INSERT INTO quickfix_gateway." + tableName + """
         (id, time, beginstring, sendercompid, sendersubid, senderlocid,
          targetcompid, targetsubid, targetlocid, session_qualifier, text)
        VALUES (2147483648, TIMESTAMP '2026-10-15 00:00:00', 'FIX.4.4', 'CLIENT', '', '',
          'SIMPLEMATCH', '', '', '', 'durable log message')
        """);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT id FROM quickfix_gateway." + tableName, Long.class)).isEqualTo(2147483648L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"status = 'SENT'", "sent_at_unix_ms = 11", "order_quantity = 0",
      "status = 'SENT', sent_at_unix_ms = -1"})
  void deliveryRejectsContradictorySendFacts(String change) {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.fix_delivery_intents (
          delivery_id, event_id, recipient_order_id, delivery_index, source_partition,
          source_offset, session_id, order_id, client_order_id, symbol, side, order_quantity,
          exec_id, exec_type, ord_status, last_quantity, last_price_units, cumulative_quantity,
          leaves_quantity, average_price_units, text, status, created_at_unix_ms
        ) VALUES (?, ?, gen_random_uuid(), 0, 0, 1, 'FIX.4.4:SIMPLEMATCH->CLIENT', 'O-C1', 'C1',
          '2330', 1, 100, 'E1', '0', '0', 0, 0, 0, 100, 0, '', 'PENDING', 10)
        """, new byte[32], new byte[32]);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quickfix_gateway.fix_delivery_intents SET " + change))
        .isInstanceOf(DataIntegrityViolationException.class);
    // A wall-clock correction must not prevent an otherwise successful send from being recorded.
    jdbcTemplate.update("UPDATE quickfix_gateway.fix_delivery_intents SET status = 'SENT', sent_at_unix_ms = 9");
  }

  @ParameterizedTest
  @ValueSource(strings = {"quarantined_at_unix_ms = -1", "status = 'RECOVERED'",
      "recovered_at_unix_ms = 11", "status = 'RECOVERED', recovered_at_unix_ms = -1"})
  void quarantineRejectsInvalidLifecycleTimes(String change) {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    jdbcTemplate.update(
        """
        INSERT INTO quickfix_gateway.matching_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, payload_sha256, reason,
          status, quarantined_at_unix_ms
        ) VALUES ('quickfix', 'matching.events.v1', 0, 1, ?, 'invalid event', 'QUARANTINED', 10)
        """, new byte[32]);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quickfix_gateway.matching_consumer_quarantines SET " + change))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbcTemplate.update("UPDATE quickfix_gateway.matching_consumer_quarantines SET status = 'RECOVERED', recovered_at_unix_ms = 9");
  }

  private DriverManagerDataSource newDataSource() {
    return PostgresTestDatabase.create("quickfix-gateway");
  }

  private void migrate(javax.sql.DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("quickfix_gateway")
        .defaultSchema("quickfix_gateway")
        .locations("classpath:db/migration/quickfix-gateway")
        .load()
        .migrate();
  }

}
