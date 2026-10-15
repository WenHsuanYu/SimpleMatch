package com.simplematch.marketdataprojection.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Verifies valid projection rows before testing individual database contract violations. */
class MarketDataProjectionFlywayMigrationTest {
  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa", "", true);
    dataSource.setDriverClassName("org.h2.Driver");
    Flyway.configure().dataSource(dataSource)
        .locations("classpath:db/migration/market-data-projection").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @ParameterizedTest
  @ValueSource(strings = {"A", "2330", "ABCDEFGHIJKL"})
  void storesTheFullSharedSymbolAndPublicationKeyContract(String symbol) {
    insertValidProjectionRows(symbol);

    assertThat(jdbc.queryForObject(
        "SELECT message_key FROM market_data_projection.market_data_events_outbox", String.class))
        .isEqualTo("XTAI:" + symbol);
  }

  @ParameterizedTest
  @MethodSource("nonCanonicalInstruments")
  void rejectsNonCanonicalInstruments(String table, String column, String value) {
    insertValidProjectionRows("2330");

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE market_data_projection." + table + " SET " + column + " = ?", value))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Stream<Arguments> nonCanonicalInstruments() {
    return Stream.of("order_book_entries", "instrument_market_data")
        .flatMap(table -> Stream.concat(
            Stream.of("abc", "a-b", "", " ", "ABCDEFGHIJKLM")
                .map(symbol -> Arguments.of(table, "symbol", symbol)),
            Stream.of("XXXX", "xtai", "", " ")
                .map(venue -> Arguments.of(table, "venue_mic", venue))));
  }

  private void insertValidProjectionRows(String symbol) {
    insertValidSnapshot(symbol);
    jdbc.update(
        """
        INSERT INTO market_data_projection.order_book_entries
          (order_id, venue_mic, symbol, side, price_units, leaves_quantity_shares, last_event_id)
        VALUES (?, 'XTAI', ?, 'B', 100, 1, ?)
        """, UUID.randomUUID(), symbol, new byte[32]);
    jdbc.update(
        """
        INSERT INTO market_data_projection.market_data_events_outbox
          (event_id, source_matching_event_id, message_key, topic, payload, created_at_unix_ms)
        VALUES (?, ?, ?, 'marketdata.events', ?, 100)
        """, new byte[32], new byte[32], "XTAI:" + symbol, new byte[] {1});

  }

  @Test
  void acceptsAnUninitializedViewWithoutAPendingRedisSnapshot() {
    assertThat(jdbc.update(
        """
        INSERT INTO market_data_projection.instrument_market_data
          (venue_mic, symbol, instrument_sequence, redis_snapshot_pending, updated_at_unix_ms)
        VALUES ('ROCO', '6488', 1, FALSE, 100)
        """))
        .isEqualTo(1);
  }

  @Test
  void acceptsNoTradeAndAPositiveCompleteTrade() {
    insertValidSnapshot("2330");

    assertThat(jdbc.update(
        """
        UPDATE market_data_projection.instrument_market_data
        SET last_trade_price_units = 100, last_trade_quantity_shares = 1
        """))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource(value = {"NULL,1", "1,NULL", "0,1", "1,0", "-1,1", "1,-1"}, nullValues = "NULL")
  void rejectsIncompleteOrNonPositiveTrade(Long price, Long quantity) {
    insertValidSnapshot("2330");

    assertThatThrownBy(() -> jdbc.update(
        """
        UPDATE market_data_projection.instrument_market_data
        SET last_trade_price_units = ?, last_trade_quantity_shares = ?
        """, price, quantity))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "snapshot_event_id = NULL", "snapshot_payload = NULL", "source_partition_id = NULL",
    "source_offset_value = NULL", "source_partition_id = -1", "source_partition_id = 15",
    "source_offset_value = -1", "venue_mic = 'XXXX'", "symbol = ''"
  })
  void rejectsIncompleteSnapshotProvenance(String invalidAssignment) {
    insertValidSnapshot("2330");

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE market_data_projection.instrument_market_data SET " + invalidAssignment))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @ParameterizedTest
  @MethodSource("invalidHashLengths")
  void rejectsHashesOtherThanSha256(String table, String column, int length) {
    insertValidProjectionRows("2330");
    jdbc.update(
        """
        INSERT INTO market_data_projection.matching_event_inbox
          (event_id, payload_sha256, partition_id, offset_value, received_at_unix_ms)
        VALUES (?, ?, 0, 0, 100)
        """, new byte[32], new byte[32]);

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE market_data_projection." + table + " SET " + column + " = ?", new byte[length]))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static Stream<Arguments> invalidHashLengths() {
    return Stream.of(
            new String[] {"matching_event_inbox", "event_id"},
            new String[] {"matching_event_inbox", "payload_sha256"},
            new String[] {"order_book_entries", "last_event_id"},
            new String[] {"instrument_market_data", "snapshot_event_id"},
            new String[] {"market_data_events_outbox", "event_id"},
            new String[] {"market_data_events_outbox", "source_matching_event_id"})
        .flatMap(field -> Stream.of(31, 33)
            .map(length -> Arguments.of(field[0], field[1], length)));
  }

  private void insertValidSnapshot(String symbol) {
    jdbc.update(
        """
        INSERT INTO market_data_projection.instrument_market_data
          (venue_mic, symbol, instrument_sequence, snapshot_event_id, snapshot_payload,
           source_partition_id, source_offset_value, redis_snapshot_pending, updated_at_unix_ms)
        VALUES ('XTAI', ?, 1, ?, ?, 0, 0, TRUE, 100)
        """, symbol, new byte[32], new byte[] {1});
  }
}
