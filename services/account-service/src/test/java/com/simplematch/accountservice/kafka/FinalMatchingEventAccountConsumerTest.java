package com.simplematch.accountservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.accountservice.matching.AccountFinalMatchingEventStatus;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountApplicationService;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountConsumer;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountHandler;
import com.simplematch.accountservice.matching.FinalMatchingEventAccountOutcome;
import com.simplematch.accountservice.reservation.AccountReservationApplicationService;
import com.simplematch.accountservice.reservation.FinalMatchingAccountEffectApplicationService;
import com.simplematch.accountservice.reservation.ReservationRequestIdentity;
import com.simplematch.accountservice.reservation.ReservationTerms;
import com.simplematch.accountservice.reservation.ReserveOperation;
import com.simplematch.accountservice.store.JdbcAccountAuthorityLifecycleWriter;
import com.simplematch.accountservice.store.JdbcAccountAuthorityReader;
import com.simplematch.accountservice.store.JdbcAccountOutboxRepository;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.config.delivery.CriticalDeliveryController;
import com.simplematch.config.delivery.DeliveryPosition;
import com.simplematch.config.delivery.QuarantineEvidence;
import com.simplematch.config.delivery.QuarantineStore;
import com.simplematch.contracts.common.v1.ReservationStatus;
import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/** Verifies native final-event bytes reach Account Authority's real application transaction. */
class FinalMatchingEventAccountConsumerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TRADING_DAY = LocalDate.of(2026, 8, 11);
  private static final String EVENT_ID_HEX =
      "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f";
  private static final byte[] EVENT_ID = HexFormat.of().parseHex(EVENT_ID_HEX);
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-0000000000aa";
  private static final String MAKER_ORDER_ID = "0198a001-0000-7000-8000-000000000011";
  private static final String TAKER_ORDER_ID = "0198a001-0000-7000-8000-000000000012";

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactions;
  private AccountReservationApplicationService reservationService;

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
        .locations("classpath:db/migration/account-service")
        .load()
        .migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    final JdbcAccountAuthorityReader reader = new JdbcAccountAuthorityReader(jdbcTemplate);
    reservationService =
        new AccountReservationApplicationService(
            reader,
            new JdbcAccountAuthorityLifecycleWriter(jdbcTemplate),
            new JdbcAccountOutboxRepository(jdbcTemplate),
            CLOCK);
    provisionNativeTradeReservations();
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void nativeTradeAppliesBothReservationsBeforeKafkaCommit() throws IOException {
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final FinalMatchingEventAccountConsumer matchingConsumer =
        new FinalMatchingEventAccountConsumer(
            realHandler(),
            controller(new RecordingQuarantineStore(), 2),
            new AccountFinalMatchingEventStatus());

    matchingConsumer.onMatchingEvent(
        record(EVENT_ID, nativeTradePayload()), acknowledgment, kafkaConsumer);

    verify(acknowledgment).acknowledge();
    verify(kafkaConsumer, never()).seek(new TopicPartition("matching.events", 0), 42L);
    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("matching_event_consumer_progress")).isEqualTo(1);
    assertThat(count("inbox")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_service.account_reservations WHERE status = ?",
                Integer.class,
                ReservationStatus.RESERVATION_STATUS_APPLIED.name()))
        .isEqualTo(2);
  }

  @Test
  void rejectsMatchingStateThatDisagreesWithAppliedReservation() throws IOException {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final FinalMatchingEventAccountConsumer matchingConsumer =
        new FinalMatchingEventAccountConsumer(
            realHandler(), controller(quarantines, 2), new AccountFinalMatchingEventStatus());
    final ConsumerRecord<byte[], byte[]> record = record(EVENT_ID, partialMakerPayload());
    final TopicPartition topicPartition = new TopicPartition("matching.events", 0);

    matchingConsumer.onMatchingEvent(record, acknowledgment, kafkaConsumer);
    matchingConsumer.onMatchingEvent(record, acknowledgment, kafkaConsumer);

    verify(kafkaConsumer).seek(topicPartition, 42L);
    verify(kafkaConsumer).pause(List.of(topicPartition));
    verify(acknowledgment, never()).acknowledge();
    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("matching_event_consumer_progress")).isZero();
    assertThat(count("inbox")).isZero();
    assertThat(quarantines.evidence).hasSize(1);
  }

  @Test
  void quarantinesWhenTheKafkaKeyDisagreesWithNativePayloadIdentity() throws IOException {
    final RecordingQuarantineStore quarantines = new RecordingQuarantineStore();
    final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    final Consumer<?, ?> kafkaConsumer = mockConsumer();
    final FinalMatchingEventAccountConsumer matchingConsumer =
        new FinalMatchingEventAccountConsumer(
            (command, partition, offset) -> FinalMatchingEventAccountOutcome.APPLIED,
            controller(quarantines, 2),
            new AccountFinalMatchingEventStatus());
    final ConsumerRecord<byte[], byte[]> record = record(new byte[32], nativeTradePayload());
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

  private FinalMatchingEventAccountHandler realHandler() {
    final JdbcAccountAuthorityReader reader = new JdbcAccountAuthorityReader(jdbcTemplate);
    final FinalMatchingAccountEffectApplicationService matchingEffectService =
        new FinalMatchingAccountEffectApplicationService(reader, reservationService);
    final FinalMatchingEventAccountApplicationService service =
        new FinalMatchingEventAccountApplicationService(
            new JdbcFinalMatchingEventAccountInbox(jdbcTemplate), matchingEffectService, CLOCK);
    return (command, partition, offset) ->
        transactions.execute(status -> service.apply(command, partition, offset));
  }

  private void provisionNativeTradeReservations() {
    transactions.executeWithoutResult(
        ignored -> {
          reservationService.provisionLimit(ACCOUNT_ID, TRADING_DAY, new BigDecimal("100000"));
          reservationService.provisionPosition(ACCOUNT_ID, "2330");
          jdbcTemplate.update(
              "UPDATE account_service.account_positions SET long_qty = 100 WHERE account_id = ?",
              UUID.fromString(ACCOUNT_ID));
          reservationService.reserve(reserve(MAKER_ORDER_ID, Side.SIDE_SELL));
          reservationService.reserve(reserve(TAKER_ORDER_ID, Side.SIDE_BUY));
        });
  }

  private ReserveOperation reserve(String orderId, Side side) {
    return new ReserveOperation(
        new ReservationRequestIdentity(
            new ReservationRequestIdentity.RequestId(UUID.randomUUID().toString()),
            new ReservationRequestIdentity.OrderId(orderId),
            new ReservationRequestIdentity.AccountId(ACCOUNT_ID)),
        new ReservationTerms(
            new ReservationTerms.InstrumentSymbol("2330"),
            new ReservationTerms.VenueMic("XTAI"),
            side,
            new ReservationTerms.ReservationQuantity(new BigDecimal("100")),
            new ReservationTerms.LimitPrice(new BigDecimal("100"))));
  }

  private CriticalDeliveryController controller(
      RecordingQuarantineStore quarantines, int attempts) {
    return new CriticalDeliveryController(
        "account-final-matching-events",
        attempts,
        "Correct Account Authority, then resume the same topic partition and offset.",
        CLOCK,
        quarantines);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM account_service." + table, Integer.class);
  }

  private Consumer<?, ?> mockConsumer() {
    return mock(Consumer.class);
  }

  private ConsumerRecord<byte[], byte[]> record(byte[] key, byte[] payload) {
    return new ConsumerRecord<>("matching.events", 0, 42L, key, payload);
  }

  private byte[] partialMakerPayload() throws IOException {
    try {
      final MatchingEvent event = MatchingEvent.parseFrom(nativeTradePayload());
      return event
          .toBuilder()
          .setTradeExecuted(
              event
                  .getTradeExecuted()
                  .toBuilder()
                  .setMaker(
                      event
                          .getTradeExecuted()
                          .getMaker()
                          .toBuilder()
                          .setLeavesQuantityShares(1)
                          .setResultingState(
                              TradeLegState.TRADE_LEG_STATE_PARTIALLY_FILLED)))
          .build()
          .toByteArray();
    } catch (InvalidProtocolBufferException invalidFixture) {
      throw new IOException("native TRADE_EXECUTED fixture is invalid", invalidFixture);
    }
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
