package com.simplematch.queryservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.contracts.matching.runtime.v1.TradeExecuted;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.contracts.matching.runtime.v1.TradeLegState;
import com.simplematch.marketreference.ArtifactChecksum;
import com.simplematch.marketreference.MarketReferenceArtifact;
import com.simplematch.marketreference.MarketReferenceArtifactCodec;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.queryservice.model.QueryFreshness;
import com.simplematch.queryservice.runtime.QueryProjectionApplicationService;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;

class JdbcQueryProjectionStoreTest {
  private static final String ORDER_ID =
      "0198a001-0000-7000-8000-000000000002";
  private static final String ACCOUNT_ID =
      "0198a001-0000-7000-8000-000000000003";
  private static final String FIRST_COMMAND_ID =
      "0198a001-0000-7000-8000-000000000001";
  private static final String LATER_COMMAND_ID =
      "0198a001-0000-7000-8000-000000000004";

  private QueryProjectionStore store;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactionManager;
  private TransactionStatus fixtureTransaction;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = QueryProjectionTestDatabase.open();
    jdbc = new JdbcTemplate(dataSource);
    store = new JdbcQueryProjectionStore(jdbc);
    transactionManager = new DataSourceTransactionManager(dataSource);
    fixtureTransaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
  }

  @AfterEach
  void rollBackFixture() {
    if (fixtureTransaction != null && !fixtureTransaction.isCompleted()) {
      transactionManager.rollback(fixtureTransaction);
    }
  }

  @Test
  void projectsMatchingAndAccountFactsWithDurableFreshness() {
    final FinalMatchingEventEnvelope matching =
        matchingEvent(FIRST_COMMAND_ID, 0L);
    store.projectMatching(matching, source("matching.events", 0, 0L, 100L));
    final AccountLifecycleEvent account = accountEvent();
    store.projectAccountLifecycle(
        account, account.toByteArray(), source("account.lifecycle", 0, 0L, 200L));

    assertThat(store.findOrder(ORDER_ID))
        .isPresent()
        .get()
        .extracting("state")
        .isEqualTo("RESTING");
    assertThat(store.findAccountSummary(ACCOUNT_ID))
        .isPresent()
        .get()
        .extracting("reservedNotionalUnits")
        .isEqualTo(1_000L);
    assertThat(store.freshness().partitions())
        .extracting(QueryFreshness.PartitionFreshness::sourceTopic)
        .containsExactly("account.lifecycle", "matching.events");
  }

  @Test
  void preservesAccountReasonWithoutConsumerSideTruncation() {
    final AccountLifecycleEvent account = accountEvent().toBuilder()
        .setReasonCode("R".repeat(255))
        .setReasonDetail("詳細原因".repeat(300))
        .build();
    store.projectAccountLifecycle(
        account, account.toByteArray(), source("account.lifecycle", 0, 0L, 200L));

    assertThat(store.findAccountSummary(ACCOUNT_ID)).isPresent().get()
        .satisfies(summary -> {
          assertThat(summary.accountId()).isEqualTo(ACCOUNT_ID);
          assertThat(summary.reasonCode()).isEqualTo(account.getReasonCode());
          assertThat(summary.reasonDetail()).isEqualTo(account.getReasonDetail());
        });
  }

  @Test
  void roundTripsUuidOrderAndExecutionIdentitiesAfterTrade() throws Exception {
    final String takerOrderId = "0198a001-0000-7000-8000-000000000005";
    store.projectMatching(matchingEvent(FIRST_COMMAND_ID, 0L),
        source("matching.events", 0, 0L, 100L));
    final MatchingEvent next = matchingEvent(LATER_COMMAND_ID, 1L).event();
    final TradeLeg maker = TradeLeg.newBuilder()
        .setOrderId(ORDER_ID).setAccountId(ACCOUNT_ID).setSide(Side.SIDE_BUY)
        .setCumulativeQuantityShares(10).setAveragePriceUnits(100_000)
        .setResultingState(TradeLegState.TRADE_LEG_STATE_FILLED).build();
    final MatchingEvent traded = next.toBuilder()
        .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_TRADE_EXECUTED)
        .setTradeExecuted(TradeExecuted.newBuilder()
            .setTradeId(ByteString.copyFrom(MatchingEventIdentityV1.tradeId(
                next.getTradingSessionId(), 0, UUID.fromString(LATER_COMMAND_ID), 0)))
            .setInstrument(next.getOrderRested().getInstrument())
            .setMaker(maker)
            .setTaker(maker.toBuilder().setOrderId(takerOrderId).setSide(Side.SIDE_SELL))
            .setAggressorSide(Side.SIDE_SELL).setQuantityShares(10).setPriceUnits(100_000))
        .build();
    final FinalMatchingEventEnvelope envelope =
        FinalMatchingEventEnvelope.parse(traded.toByteArray());
    store.projectMatching(envelope, source("matching.events", 0, 1L, 101L));
    store.projectMatching(envelope, source("matching.events", 0, 2L, 102L));

    assertThat(store.findOrder(ORDER_ID)).isPresent().get()
        .satisfies(order -> assertThat(order.state()).isEqualTo("FILLED"));
    assertThat(store.findExecutions(ORDER_ID)).singleElement()
        .satisfies(execution -> {
          assertThat(UUID.fromString(execution.executionId())).isNotNull();
          assertThat(execution.orderId()).isEqualTo(ORDER_ID);
          assertThat(execution.accountId()).isEqualTo(ACCOUNT_ID);
        });
    assertThat(store.findExecutions(takerOrderId)).hasSize(1);
    assertThat(store.findOrder(takerOrderId)).isPresent().get()
        .satisfies(order -> assertThat(order.state()).isEqualTo("FILLED"));
    assertThat(store.freshness().partitions()).singleElement()
        .extracting(QueryFreshness.PartitionFreshness::lastProcessedOffset)
        .isEqualTo(2L);
  }

  @Test
  void installsAndReadsVerifiedReferenceWithSqlDateAndCurrentArtifactIdentity() throws Exception {
    final VerifiedMarketReferenceArtifact artifact = verifiedArtifact();
    store.installMarketReference(artifact, 100L);
    store.installMarketReference(artifact, 101L);

    assertThat(store.findMarketReference("2026-08-11", "XTAI", "2330"))
        .isPresent().get().satisfies(reference -> {
          assertThat(reference.tradingDay()).isEqualTo(LocalDate.of(2026, 8, 11));
          assertThat(reference.artifactId()).isEqualTo(artifact.identity().value());
          assertThat(reference.referencePriceUnits()).isEqualTo(1_000_000L);
          assertThat(reference.lowerPriceLimitUnits()).isEqualTo(900_000L);
          assertThat(reference.upperPriceLimitUnits()).isEqualTo(1_100_000L);
          assertThat(reference.routingPartition()).isZero();
          assertThat(reference.updatedAtUnixMs()).isEqualTo(101L);
        });
  }

  @Test
  void applicationTransactionRollsBackInboxAndModelWhenCheckpointWriteFails() {
    // The production interceptor must own this transaction, including its real rollback.
    transactionManager.rollback(fixtureTransaction);
    jdbc.execute("ALTER TABLE query_service.projection_checkpoint "
        + "ADD CONSTRAINT query_jdbc_test_checkpoint_failure "
        + "CHECK (source_topic <> 'rollback-test')");
    try {
      final QueryProjectionApplicationService application = transactionalApplicationService();

      assertThatThrownBy(() -> application.projectMatching(
          matchingEvent(FIRST_COMMAND_ID, 0L), source("rollback-test", 0, 0L, 100L)))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("projection_checkpoint");

      assertThat(store.findOrder(ORDER_ID)).isEmpty();
      assertThat(store.freshness().partitions()).isEmpty();
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM query_service.projection_inbox", Long.class)).isZero();
    } finally {
      jdbc.execute("ALTER TABLE query_service.projection_checkpoint "
          + "DROP CONSTRAINT query_jdbc_test_checkpoint_failure");
    }
  }

  @Test
  void equivalentAccountDuplicateAdvancesTransportProgressWithoutSecondProjection() {
    final AccountLifecycleEvent first = accountEvent();
    store.projectAccountLifecycle(
        first, first.toByteArray(), source("account.lifecycle", 0, 0L, 200L));

    store.projectAccountLifecycle(
        first, first.toByteArray(), source("account.lifecycle", 0, 1L, 201L));

    final AccountLifecycleEvent next =
        first.toBuilder()
            .setMetadata(
                first.getMetadata().toBuilder()
                    .setEventId("0198a001-0000-7000-8000-000000000011")
                    .setCreatedAtUnixMs(202L))
            .setReservedNotional(
                TwdNotional.newBuilder().setUnits(2_000L))
            .build();
    store.projectAccountLifecycle(
        next, next.toByteArray(), source("account.lifecycle", 0, 2L, 202L));

    assertThat(store.findAccountSummary(ACCOUNT_ID))
        .isPresent()
        .get()
        .extracting("reservedNotionalUnits")
        .isEqualTo(2_000L);
    assertThat(store.freshness().partitions())
        .singleElement()
        .extracting(QueryFreshness.PartitionFreshness::lastProcessedOffset)
        .isEqualTo(2L);
  }

  @Test
  void conflictingAccountDuplicateFailsClosed() {
    final AccountLifecycleEvent first = accountEvent();
    final byte[] firstPayload = first.toByteArray();
    store.projectAccountLifecycle(
        first, firstPayload, source("account.lifecycle", 0, 0L, 200L));
    final byte[] conflictingPayload = firstPayload.clone();
    conflictingPayload[conflictingPayload.length - 1] ^= 0x01;

    assertThatThrownBy(
            () ->
                store.projectAccountLifecycle(
                    first,
                    conflictingPayload,
                    source("account.lifecycle", 0, 1L, 201L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(first.getMetadata().getEventId());
  }

  @Test
  void equivalentAccountDuplicateFromDifferentTopicFailsClosed() {
    final AccountLifecycleEvent first = accountEvent();
    final byte[] payload = first.toByteArray();
    store.projectAccountLifecycle(
        first, payload, source("account.lifecycle", 0, 0L, 200L));

    assertThatThrownBy(
            () ->
                store.projectAccountLifecycle(
                    first,
                    payload,
                    source("account.lifecycle.replayed", 0, 1L, 201L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed source topic")
        .hasMessageContaining(first.getMetadata().getEventId());
  }

  @Test
  void equivalentAccountDuplicateFromDifferentPartitionFailsClosed() {
    final AccountLifecycleEvent first = accountEvent();
    final byte[] payload = first.toByteArray();
    store.projectAccountLifecycle(
        first, payload, source("account.lifecycle", 0, 0L, 200L));

    assertThatThrownBy(
            () ->
                store.projectAccountLifecycle(
                    first,
                    payload,
                    source("account.lifecycle", 1, 1L, 201L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed Kafka partition")
        .hasMessageContaining(first.getMetadata().getEventId());
  }

  @Test
  void equivalentAccountDuplicateBeforeOriginalOffsetFailsClosed() {
    final AccountLifecycleEvent first = accountEvent();
    final byte[] payload = first.toByteArray();
    store.projectAccountLifecycle(
        first, payload, source("account.lifecycle", 0, 5L, 200L));

    assertThatThrownBy(
            () ->
                store.projectAccountLifecycle(
                    first,
                    payload,
                    source("account.lifecycle", 0, 4L, 201L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before original Kafka offset")
        .hasMessageContaining(first.getMetadata().getEventId());
  }

  @Test
  void recordsGapAndRequiresReplayBeforeApplyingLaterOffset() {
    store.projectMatching(
        matchingEvent(FIRST_COMMAND_ID, 0L), source("matching.events", 0, 0L, 100L));
    final FinalMatchingEventEnvelope laterEnvelope =
        matchingEvent(LATER_COMMAND_ID, 2L);

    assertThatThrownBy(
            () ->
                store.projectMatching(
                    laterEnvelope, source("matching.events", 0, 2L, 300L)))
        .isInstanceOf(QueryProjectionGapException.class);
    store.markRecoveryRequired(source("matching.events", 0, 2L, 300L));
    assertThat(store.freshness().partitions().getFirst().recoveryState())
        .isEqualTo("GAP_DETECTED");

    store.resetForReplay();
    assertThat(store.findOrder(ORDER_ID)).isEmpty();
    assertThat(store.freshness().partitions()).isEmpty();
  }

  private FinalMatchingEventEnvelope matchingEvent(
      String commandId, long sourceOffset) {
    final MatchingEvent event =
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId(
                ByteString.copyFrom(
                    MatchingEventIdentityV1.eventId(
                        "2026-08-11-regular",
                        0,
                        UUID.fromString(commandId),
                        0)))
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId(commandId)
            .setSourceInputOffset(sourceOffset)
            .setOutputIndex(0)
            .setArtifactIdentity(
                ArtifactIdentity.newBuilder()
                    .setTradingDay("2026-08-11")
                    .setContentSha256(
                        "7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943"))
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(
                MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setAccountId(ACCOUNT_ID)
                    .setInstrument(
                        VenueInstrument.newBuilder()
                            .setVenueMic("XTAI")
                            .setSymbol("2330"))
                    .setSide(Side.SIDE_BUY)
                    .setLeavesQuantityShares(10)
                    .setRestingPriceUnits(100_000))
            .build();
    try {
      return FinalMatchingEventEnvelope.parse(event.toByteArray());
    } catch (InvalidProtocolBufferException invalidFixture) {
      throw new AssertionError(
          "test Matching Event fixture must be valid", invalidFixture);
    }
  }

  private QueryProjectionApplicationService transactionalApplicationService() {
    final TransactionInterceptor interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(transactionManager);
    interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    final ProxyFactory proxy = new ProxyFactory(new QueryProjectionApplicationService(store));
    proxy.addAdvice(interceptor);
    return (QueryProjectionApplicationService) proxy.getProxy();
  }

  private VerifiedMarketReferenceArtifact verifiedArtifact() throws Exception {
    try (InputStream resource = getClass().getResourceAsStream(
        "/market-reference-fixtures/market_reference.json")) {
      assertThat(resource).isNotNull();
      final byte[] bytes = resource.readAllBytes();
      final String checksum = ArtifactChecksum.sha256(bytes);
      final LocalDate tradingDay = LocalDate.of(2026, 8, 11);
      final MarketReferenceArtifact artifact = new MarketReferenceArtifactCodec(new ObjectMapper())
          .readVerified(bytes, checksum, tradingDay);
      return new VerifiedMarketReferenceArtifact(
          artifact, com.simplematch.marketreference.ArtifactIdentity.of(tradingDay, checksum));
    }
  }

  private QueryProjectionSource source(
      String topic, int partition, long offset, long observedAtUnixMs) {
    return new QueryProjectionSource(topic, partition, offset, observedAtUnixMs);
  }

  private AccountLifecycleEvent accountEvent() {
    return AccountLifecycleEvent.newBuilder()
        .setMetadata(
            EventMetadata.newBuilder()
                .setSchemaVersion("v2")
                .setEventId("0198a001-0000-7000-8000-000000000010")
                .setCreatedAtUnixMs(200L)
                .setSourceService("account-service"))
        .setReservationId(ORDER_ID)
        .setOrderId(ORDER_ID)
        .setAccountId(ACCOUNT_ID)
        .setState(
            AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED)
        .setReservedNotional(TwdNotional.newBuilder().setUnits(1_000))
        .setReasonCode("")
        .setReasonDetail("")
        .build();
  }
}
