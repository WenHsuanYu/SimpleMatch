package com.simplematch.queryservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.simplematch.queryservice.model.QueryFreshness;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:querytest"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS query_service\\;"
            + "SET SCHEMA query_service");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/query-service")
        .load()
        .migrate();
    store = new JdbcQueryProjectionStore(new JdbcTemplate(dataSource));
  }

  @Test
  void projectsMatchingAndAccountFactsWithDurableFreshness() {
    final FinalMatchingEventEnvelope matching =
        matchingEvent(FIRST_COMMAND_ID, 0L);
    store.projectMatching(matching, 0, 0, 100L);
    final AccountLifecycleEvent account = accountEvent();
    store.projectAccountLifecycle(account, account.toByteArray(), 0, 0, 200L);

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
  void equivalentAccountDuplicateAdvancesTransportProgressWithoutSecondProjection() {
    final AccountLifecycleEvent first = accountEvent();
    store.projectAccountLifecycle(first, first.toByteArray(), 0, 0, 200L);

    store.projectAccountLifecycle(first, first.toByteArray(), 0, 1, 201L);

    final AccountLifecycleEvent next =
        first.toBuilder()
            .setMetadata(
                first.getMetadata().toBuilder()
                    .setEventId("0198a001-0000-7000-8000-000000000011")
                    .setCreatedAtUnixMs(202L))
            .setReservedNotional(
                TwdNotional.newBuilder().setUnits(2_000L))
            .build();
    store.projectAccountLifecycle(next, next.toByteArray(), 0, 2, 202L);

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
    store.projectAccountLifecycle(first, firstPayload, 0, 0, 200L);
    final byte[] conflictingPayload = firstPayload.clone();
    conflictingPayload[conflictingPayload.length - 1] ^= 0x01;

    assertThatThrownBy(
            () ->
                store.projectAccountLifecycle(
                    first, conflictingPayload, 0, 1, 201L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(first.getMetadata().getEventId());
  }

  @Test
  void recordsGapAndRequiresReplayBeforeApplyingLaterOffset() {
    store.projectMatching(matchingEvent(FIRST_COMMAND_ID, 0L), 0, 0, 100L);
    final FinalMatchingEventEnvelope laterEnvelope =
        matchingEvent(LATER_COMMAND_ID, 2L);

    assertThatThrownBy(
            () -> store.projectMatching(laterEnvelope, 0, 2, 300L))
        .isInstanceOf(QueryProjectionGapException.class);
    store.markRecoveryRequired("matching.events", 0, 2, 300L);
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
