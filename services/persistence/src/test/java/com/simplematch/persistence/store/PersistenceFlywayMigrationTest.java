package com.simplematch.persistence.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.config.testsupport.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistenceFlywayMigrationTest {
  @DisplayName("active trade facts accept valid rows and reject a zero quantity")
  @Test
  void constraintsRejectInvalidBusinessValues() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    jdbcTemplate.update(
        """
        INSERT INTO persistence.trades (
          trade_id, event_id, trading_day, trading_session_id, partition_id, source_input_offset,
          venue_mic, symbol, quantity_shares, price_units
        ) VALUES (?, ?, DATE '2026-10-15', 'CONTINUOUS', 0, 1, 'XTAI', '2330', 100, 10000)
        """, new byte[32], new byte[32]);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM persistence.trades", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE persistence.trades SET quantity_shares = 0"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @DisplayName("quarantine status and recovery timestamp must describe the same lifecycle")
  @Test
  void constraintsRejectInconsistentQuarantineRecovery() {
    final JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
    migrate(jdbcTemplate.getDataSource());
    jdbcTemplate.update(
        """
        INSERT INTO persistence.matching_consumer_quarantines (
          consumer_name, topic, partition_id, offset_value, payload_sha256, reason,
          status, quarantined_at_unix_ms
        ) VALUES ('persistence', 'matching.events.v1', 0, 1, ?, 'invalid event', 'QUARANTINED', 10)
        """, new byte[32]);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE persistence.matching_consumer_quarantines SET status = 'RECOVERED'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE persistence.matching_consumer_quarantines SET recovered_at_unix_ms = 11"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbcTemplate.update("UPDATE persistence.matching_consumer_quarantines SET status = 'RECOVERED', recovered_at_unix_ms = 11");
  }

  private DriverManagerDataSource newDataSource() {
    return PostgresTestDatabase.create("persistence");
  }

  private void migrate(javax.sql.DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("persistence")
        .defaultSchema("persistence")
        .locations("classpath:db/migration/persistence")
        .load()
        .migrate();
  }

}
