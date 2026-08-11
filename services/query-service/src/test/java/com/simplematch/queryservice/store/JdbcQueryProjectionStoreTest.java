package com.simplematch.queryservice.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.account.v2.AccountLifecycleEvent;
import com.simplematch.contracts.account.v2.AccountLifecycleState;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TwdNotional;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.contracts.matching.runtime.v1.ArtifactIdentity;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.MatchingEventType;
import com.simplematch.contracts.matching.runtime.v1.OrderRested;
import com.simplematch.queryservice.model.QueryFreshness;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcQueryProjectionStoreTest {
  private static final String ORDER_ID = "0198a001-0000-7000-8000-000000000002";
  private static final String ACCOUNT_ID = "0198a001-0000-7000-8000-000000000003";

  private JdbcTemplate jdbcTemplate;
  private JdbcQueryProjectionStore store;

  @BeforeEach
  void setUp() {
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl(
        "jdbc:h2:mem:querytest"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS query_service\\;SET SCHEMA query_service");
    jdbcTemplate = new JdbcTemplate(dataSource);
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/query-service")
        .load()
        .migrate();
    store = new JdbcQueryProjectionStore(jdbcTemplate);
  }

  @Test
  void projectsMatchingAndAccountFactsWithDurableFreshness() {
    final FinalMatchingEventEnvelope matching = matchingEvent();
    store.projectMatching(matching, 0, 0, 100L);
    final AccountLifecycleEvent account = accountEvent();
    store.projectAccountLifecycle(account, account.toByteArray(), 0, 0, 200L);

    assertThat(store.findOrder(ORDER_ID)).isPresent().get().extracting("state").isEqualTo("RESTING");
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
  void recordsGapAndRequiresReplayBeforeApplyingLaterOffset() {
    store.projectMatching(matchingEvent(), 0, 0, 100L);
    final MatchingEvent later =
        matchingEvent().event().toBuilder().setEventId("ab".repeat(32)).build();
    final FinalMatchingEventEnvelope laterEnvelope =
        new FinalMatchingEventEnvelope(
            later, later.toByteArray(), FinalMatchingEventEnvelope.sha256(later.toByteArray()));

    assertThatThrownBy(() -> store.projectMatching(laterEnvelope, 0, 2, 300L))
        .isInstanceOf(QueryProjectionGapException.class);
    store.markRecoveryRequired("matching.events", 0, 2, 300L);
    assertThat(store.freshness().partitions().getFirst().recoveryState()).isEqualTo("GAP_DETECTED");

    store.resetForReplay();
    assertThat(store.findOrder(ORDER_ID)).isEmpty();
    assertThat(store.freshness().partitions()).isEmpty();
  }

  private FinalMatchingEventEnvelope matchingEvent() {
    final MatchingEvent event =
        MatchingEvent.newBuilder()
            .setSchemaVersion(1)
            .setIdentityVersion(1)
            .setEventId("beef".repeat(16))
            .setTradingSessionId("2026-08-11-regular")
            .setPartitionId(0)
            .setSourceCommandId("0198a001-0000-7000-8000-000000000001")
            .setSourceInputOffset(0)
            .setOutputIndex(0)
            .setArtifactIdentity(
                ArtifactIdentity.newBuilder()
                    .setTradingDay("2026-08-11")
                    .setContentSha256("7cd06c51691bcde248e606ed1adfaddc4bd10ece582a6803fd2f04155a032943"))
            .setRoutingAlgorithmVersion("stable-least-loaded-v1")
            .setEventType(MatchingEventType.MATCHING_EVENT_TYPE_ORDER_RESTED)
            .setOrderRested(
                OrderRested.newBuilder()
                    .setOrderId(ORDER_ID)
                    .setAccountId(ACCOUNT_ID)
                    .setInstrument(VenueInstrument.newBuilder().setVenueMic("XTAI").setSymbol("2330"))
                    .setSide(Side.SIDE_BUY)
                    .setLeavesQuantityShares(10)
                    .setRestingPriceUnits(100_000))
            .build();
    return new FinalMatchingEventEnvelope(
        event, event.toByteArray(), FinalMatchingEventEnvelope.sha256(event.toByteArray()));
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
        .setState(AccountLifecycleState.ACCOUNT_LIFECYCLE_STATE_RESERVED)
        .setReservedNotional(TwdNotional.newBuilder().setUnits(1_000))
        .setReasonCode("")
        .setReasonDetail("")
        .build();
  }
}
