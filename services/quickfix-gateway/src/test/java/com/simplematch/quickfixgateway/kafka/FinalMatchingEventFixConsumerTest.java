package com.simplematch.quickfixgateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import com.simplematch.quickfixgateway.fix.OrderSessionRegistry;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryApplicationService;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryHandler;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryOutcome;
import com.simplematch.quickfixgateway.matching.FinalMatchingEventFixDeliveryPlanner;
import com.simplematch.quickfixgateway.matching.QuickFixFinalMatchingEventStatus;
import com.simplematch.quickfixgateway.store.JdbcFinalFixDeliveryStore;
import com.simplematch.quickfixgateway.wal.FixSessionIdentity;
import com.simplematch.quickfixgateway.wal.RawFixMessage;
import com.simplematch.quickfixgateway.wal.WalCommand;
import com.simplematch.quickfixgateway.wal.WalMetadata;
import com.simplematch.quickfixgateway.wal.WalOrderReference;
import com.simplematch.quickfixgateway.wal.WalOrderTerms;
import com.simplematch.quickfixgateway.wal.WalRecord;
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
import quickfix.SessionID;

/** Verifies native final-event bytes reach QuickFIX's real application transaction. */
class FinalMatchingEventFixConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final String EVENT_ID_HEX =
      "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f";
  private static final byte[] EVENT_ID = HexFormat.of().parseHex(EVENT_ID_HEX);
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";
  private static final String MAKER_ORDER_ID = "0198a001-0000-7000-8000-000000000011";
  private static final String TAKER_ORDER_ID = "0198a001-0000-7000-8000-000000000012";

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
        .locations("classpath:db/migration/quickfix-gateway")
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
  void nativeTradeReachesTheRealDeliveryApplicationBeforeKafkaCommit() throws IOException {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final FinalMatchingEventFixConsumer finalEventConsumer =
        new FinalMatchingEventFixConsumer(
            realHandler(),
            controller(new RecordingQuarantineStore(), 2),
            new QuickFixFinalMatchingEventStatus());

    finalEventConsumer.onMatchingEvent(record(EVENT_ID), acknowledgment, kafkaConsumer);

    verify(acknowledgment).acknowledge();
    verify(kafkaConsumer, never()).seek(new TopicPartition("matching.events", 0), 42L);
    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("fix_delivery_intents")).isEqualTo(2);
    assertThat(count("matching_consumer_progress")).isEqualTo(1);
  }

  @Test
  void restoredQuarantineStopsDeliveryBeforeApplicationTransaction() throws IOException {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final CriticalDeliveryController controller = controller(quarantines, 2);
    final DeliveryPosition blocked = new DeliveryPosition("matching.events", 0, 42L);
    controller.restoreQuarantines(List.of(blocked));
    final QuickFixFinalMatchingEventStatus status = new QuickFixFinalMatchingEventStatus();
    status.recordQuarantined(blocked);
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final FinalMatchingEventFixConsumer finalEventConsumer =
        new FinalMatchingEventFixConsumer(realHandler(), controller, status);
    final TopicPartition topicPartition = new TopicPartition("matching.events", 0);

    finalEventConsumer.onMatchingEvent(record(EVENT_ID), acknowledgment, kafkaConsumer);

    verify(kafkaConsumer).seek(topicPartition, 42L);
    verify(kafkaConsumer).pause(List.of(topicPartition));
    verify(acknowledgment, never()).acknowledge();
    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("fix_delivery_intents")).isZero();
    assertThat(count("matching_consumer_progress")).isZero();
  }

  @Test
  void quarantinesWhenTheKafkaKeyDisagreesWithNativePayloadIdentity() throws IOException {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final FinalMatchingEventFixConsumer finalEventConsumer =
        new FinalMatchingEventFixConsumer(
            (envelope, partition, offset) -> FinalMatchingEventFixDeliveryOutcome.APPLIED,
            controller(quarantines, 2),
            new QuickFixFinalMatchingEventStatus());
    final ConsumerRecord<byte[], byte[]> record = record(new byte[32]);
    final TopicPartition topicPartition = new TopicPartition("matching.events", 0);

    finalEventConsumer.onMatchingEvent(record, acknowledgment, kafkaConsumer);
    finalEventConsumer.onMatchingEvent(record, acknowledgment, kafkaConsumer);

    verify(kafkaConsumer).seek(topicPartition, 42L);
    verify(kafkaConsumer).pause(List.of(topicPartition));
    verify(acknowledgment, never()).acknowledge();
    assertThat(quarantines.evidence)
        .singleElement()
        .satisfies(evidence -> assertThat(evidence.record().eventId()).isEqualTo(EVENT_ID_HEX));
  }

  private FinalMatchingEventFixDeliveryHandler realHandler() {
    final OrderSessionRegistry registry = new OrderSessionRegistry();
    register(registry, MAKER_ORDER_ID, Side.SIDE_SELL, "M-1");
    register(registry, TAKER_ORDER_ID, Side.SIDE_BUY, "T-1");
    final FinalMatchingEventFixDeliveryApplicationService service =
        new FinalMatchingEventFixDeliveryApplicationService(
            new JdbcFinalFixDeliveryStore(jdbcTemplate),
            new FinalMatchingEventFixDeliveryPlanner(registry),
            CLOCK);
    return (envelope, partition, offset) ->
        transactions.execute(status -> service.persist(envelope, partition, offset));
  }

  private void register(
      OrderSessionRegistry registry, String orderId, Side side, String clientOrderId) {
    registry.registerAcceptedOrder(
        new SessionID("FIX.4.4", "SIMPLEMATCH", "CLIENT"),
        new WalRecord(
            new WalMetadata("v1", UUID.randomUUID().toString(), 1L, "quickfix-gateway"),
            new FixSessionIdentity("CLIENT", "SIMPLEMATCH"),
            new WalOrderReference(orderId, clientOrderId, "", ACCOUNT_ID),
            new WalCommand.NewOrder(
                new WalOrderTerms(
                    "2330",
                    side,
                    "100",
                    "100",
                    OrderType.ORDER_TYPE_LIMIT,
                    TimeInForce.TIME_IN_FORCE_ROD)),
            new RawFixMessage("raw")),
        'A');
  }

  private CriticalDeliveryController controller(
      RecordingQuarantineStore quarantines, int attempts) {
    return new CriticalDeliveryController(
        "quickfix-final-matching-events",
        attempts,
        "Correct Gateway delivery, then resume the same topic partition and offset.",
        CLOCK,
        quarantines);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM quickfix_gateway." + table, Integer.class);
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
