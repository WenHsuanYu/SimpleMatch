package com.simplematch.persistence.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.persistence.matching.MatchingEventPersistenceApplicationService;
import com.simplematch.persistence.matching.MatchingEventPersistenceHandler;
import com.simplematch.persistence.matching.MatchingEventPersistenceOutcome;
import com.simplematch.persistence.store.JdbcMatchingEventStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies native final-event bytes reach Persistence's real application transaction. */
class PersistenceMatchingEventConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String EVENT_ID_HEX =
      "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f";
  private static final byte[] EVENT_ID = HexFormat.of().parseHex(EVENT_ID_HEX);

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    dataSource =
        new SingleConnectionDataSource(
            "jdbc:h2:mem:"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true);
    dataSource.setDriverClassName("org.h2.Driver");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/persistence")
        .load()
        .migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void nativeTradeReachesTheRealPersistenceApplicationBeforeKafkaCommit() throws IOException {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final PersistenceMatchingEventConsumer matchingConsumer =
        new PersistenceMatchingEventConsumer(
            realHandler(),
            controller(new RecordingQuarantineStore(), 2),
            new PersistenceMatchingEventStatus());

    matchingConsumer.onMatchingEvent(record(EVENT_ID), acknowledgment, kafkaConsumer);

    verify(acknowledgment).acknowledge();
    verify(kafkaConsumer, never()).seek(new TopicPartition("matching.events", 0), 42L);
    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("trades")).isEqualTo(1);
    assertThat(count("order_fills")).isEqualTo(2);
    assertThat(count("matching_consumer_progress")).isEqualTo(1);
  }

  @Test
  void quarantinesWhenTheKafkaKeyDisagreesWithNativePayloadIdentity() throws IOException {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final PersistenceMatchingEventConsumer matchingConsumer =
        new PersistenceMatchingEventConsumer(
            (envelope, partition, offset) -> MatchingEventPersistenceOutcome.APPLIED,
            controller(quarantines, 2),
            new PersistenceMatchingEventStatus());
    final ConsumerRecord<byte[], byte[]> record = record(new byte[32]);
    final TopicPartition topicPartition = new TopicPartition("matching.events", 0);

    matchingConsumer.onMatchingEvent(record, acknowledgment, kafkaConsumer);
    matchingConsumer.onMatchingEvent(record, acknowledgment, kafkaConsumer);

    verify(kafkaConsumer).seek(topicPartition, 42L);
    verify(kafkaConsumer).pause(List.of(topicPartition));
    verify(acknowledgment, never()).acknowledge();
    assertThat(quarantines.evidence)
        .singleElement()
        .satisfies(evidence -> assertThat(evidence.record().eventId()).isEqualTo(EVENT_ID_HEX));
  }

  private MatchingEventPersistenceHandler realHandler() {
    final MatchingEventPersistenceApplicationService service =
        new MatchingEventPersistenceApplicationService(new JdbcMatchingEventStore(jdbcTemplate), CLOCK);
    return (envelope, partition, offset) ->
        transactions.execute(status -> service.persist(envelope, partition, offset));
  }

  private CriticalDeliveryController controller(
      RecordingQuarantineStore quarantines, int attempts) {
    return new CriticalDeliveryController(
        "persistence-matching-events",
        attempts,
        "Correct the durable Persistence state, then resume the same topic partition and offset.",
        CLOCK,
        quarantines);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM persistence." + table, Integer.class);
  }

  private Consumer<?, ?> mockConsumer() {
    return mock(Consumer.class);
  }

  private ConsumerRecord<byte[], byte[]> record(byte[] key) throws IOException {
    return new ConsumerRecord<>("matching.events", 0, 42L, key, nativeTradePayload());
  }

  private byte[] nativeTradePayload() throws IOException {
    try (InputStream stream =
        getClass()
            .getResourceAsStream(
                "/native-routing-fixtures/cpp-matching-trade-executed-v1.hex")) {
      if (stream == null) {
        throw new IOException("missing native TRADE_EXECUTED fixture");
      }
      final String encoded =
          new String(stream.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
      return HexFormat.of().parseHex(encoded);
    }
  }

  private static final class RecordingQuarantineStore implements QuarantineStore {
    private final List<QuarantineEvidence> evidence = new ArrayList<>();

    @Override
    public void save(QuarantineEvidence quarantine) {
      evidence.add(quarantine);
    }

    @Override
    public void markRecovered(DeliveryPosition position, long recoveredAtUnixMs) {
      // This test only verifies creation of evidence for the exact blocked record.
    }
  }
}
