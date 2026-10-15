package com.simplematch.riskservice.store;

import static com.simplematch.riskservice.testsupport.H2TestDatabaseUrl.uniquePostgresModeUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Tests journal invariants against a complete valid row, changing one condition at a time. */
class AdmissionJournalConstraintsTest {
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void migrateAndInsertValidPendingNewOrder() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(uniquePostgresModeUrl());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/risk-service")
        .load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        """
        INSERT INTO risk_service.admission_journal (
          command_id, order_id, account_id, symbol, venue_mic, side, quantity,
          limit_price_units, order_type, tif, trading_day, sender_comp_id, target_comp_id,
          cl_ord_id, routing_partition, artifact_trading_day, artifact_content_sha256,
          routing_algorithm_version, state, created_at_unix_ms, updated_at_unix_ms
        ) VALUES (RANDOM_UUID(), RANDOM_UUID(), RANDOM_UUID(), '2330', 'XTAI', 'SIDE_BUY', 100,
          10000, 'ORDER_TYPE_LIMIT', 'TIME_IN_FORCE_ROD', DATE '2026-10-15', 'CLIENT',
          'SIMPLEMATCH', 'C-1', 0, DATE '2026-10-15', ?, 'symbol-sha256-v1', 'PENDING', 1, 1)
        """, "a".repeat(64));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "order_type = 'UNKNOWN'", "tif = 'UNKNOWN'", "limit_price_units = 0",
      "routing_partition = 15", "created_at_unix_ms = -1", "updated_at_unix_ms = -1",
      "artifact_content_sha256 = REPEAT('z', 64)", "sender_comp_id = REPEAT('A', 65)",
      "target_comp_id = REPEAT('A', 65)", "cl_ord_id = REPEAT('A', 65)",
      "state = 'ACCEPTED'", "order_type = 'CANCEL'", "tif = 'CANCEL'"
  })
  void rejectsAnInvalidChangeToOtherwiseValidNewOrder(String change) {
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE risk_service.admission_journal SET " + change))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void acceptedNewRequiresReservationWhileAcceptedCancelHasNone() {
    jdbcTemplate.update("UPDATE risk_service.admission_journal SET state = 'ACCEPTED', reservation_id = RANDOM_UUID()");
    jdbcTemplate.update("UPDATE risk_service.admission_journal SET order_type = 'CANCEL', tif = 'CANCEL', reservation_id = NULL, limit_price_units = NULL");
    assertThatThrownBy(() -> jdbcTemplate.update("UPDATE risk_service.admission_journal SET reservation_id = RANDOM_UUID()"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.admission_journal WHERE state = 'ACCEPTED' AND reservation_id IS NULL", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void pendingAndRejectedMarketOrdersPermitNoLimitPriceOrReservation() {
    jdbcTemplate.update("UPDATE risk_service.admission_journal SET order_type = 'ORDER_TYPE_MARKET', limit_price_units = NULL");
    jdbcTemplate.update("UPDATE risk_service.admission_journal SET state = 'REJECTED'");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM risk_service.admission_journal", Integer.class))
        .isEqualTo(1);
  }
}
