package com.simplematch.accountservice.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.accountservice.reservation.AccountMatchingExecutionHandler;
import com.simplematch.accountservice.reservation.ExecutionFill;
import com.simplematch.accountservice.reservation.MatchingAccountEffect;
import com.simplematch.accountservice.reservation.ReleaseReservationOperation;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.contracts.matching.runtime.v1.DeterministicEventConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies Account's final-event application Interface owns one local transaction. */
class FinalMatchingEventAccountApplicationServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
  private static final byte[] EVENT_ID = bytes(1);
  private static final byte[] PAYLOAD_HASH = bytes(2);

  private SingleConnectionDataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private JdbcFinalMatchingEventAccountInbox inbox;

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
    inbox = new JdbcFinalMatchingEventAccountInbox(jdbcTemplate);
  }

  @AfterEach
  void tearDown() {
    dataSource.destroy();
  }

  @Test
  void appliesTranslatedEffectsAndProgressAfterClaimingTheExactEvent() {
    final List<MatchingAccountEffect> applied = new ArrayList<>();
    final FinalMatchingEventAccountApplicationService service = service(applied::add);
    final FinalMatchingEventAccountCommand command = command(PAYLOAD_HASH, effects());

    assertThat(service.apply(command, 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertThat(applied).containsExactlyElementsOf(effects());
    assertThat(count("matching_event_inbox")).isEqualTo(1);
    assertThat(count("matching_event_consumer_progress")).isEqualTo(1);
  }

  @Test
  void deduplicatesExactReplayBeforeApplyingAccountEffects() {
    final List<MatchingAccountEffect> applied = new ArrayList<>();
    final FinalMatchingEventAccountApplicationService service = service(applied::add);
    final FinalMatchingEventAccountCommand command = command(PAYLOAD_HASH, effects());

    assertThat(service.apply(command, 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);
    assertThat(service.apply(command, 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.DUPLICATE);

    assertThat(applied).containsExactlyElementsOf(effects());
  }

  @Test
  void failsClosedWhenTheSameEventIdentityHasDifferentRawValueEvidence() {
    final FinalMatchingEventAccountApplicationService service = service(ignored -> {});

    assertThat(service.apply(command(PAYLOAD_HASH, List.of()), 0, 42L))
        .isEqualTo(FinalMatchingEventAccountOutcome.APPLIED);

    assertThatThrownBy(() -> service.apply(command(bytes(3), List.of()), 0, 42L))
        .isInstanceOf(DeterministicEventConflictException.class);
  }

  @Test
  void rollsBackInboxAndProgressWhenAnAccountEffectFails() {
    final FinalMatchingEventAccountApplicationService service =
        service(
            effect -> {
              throw new IllegalStateException("account authority is unavailable");
            });
    final TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored -> service.apply(command(PAYLOAD_HASH, effects()), 0, 42L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("account authority is unavailable");

    assertThat(count("matching_event_inbox")).isZero();
    assertThat(count("matching_event_consumer_progress")).isZero();
  }

  private FinalMatchingEventAccountApplicationService service(EffectSink sink) {
    final AccountMatchingExecutionHandler handler =
        effect -> {
          sink.accept(effect);
          return null;
        };
    return new FinalMatchingEventAccountApplicationService(inbox, handler, CLOCK);
  }

  private FinalMatchingEventAccountCommand command(
      byte[] payloadHash, List<MatchingAccountEffect> effects) {
    return new FinalMatchingEventAccountCommand(EVENT_ID, payloadHash, effects);
  }

  private List<MatchingAccountEffect> effects() {
    return List.of(
        new MatchingAccountEffect.Fill(
            "fill-1",
            "0198a001-0000-7000-8000-000000000011",
            "0198a001-0000-7000-8000-0000000000aa",
            "2330",
            new ExecutionFill.FillQuantity(new BigDecimal("100")),
            new ExecutionFill.FillPrice(new BigDecimal("100.0000"))),
        new MatchingAccountEffect.Terminal(
            "terminal-1",
            "0198a001-0000-7000-8000-000000000012",
            "0198a001-0000-7000-8000-0000000000aa",
            "2330",
            new ReleaseReservationOperation.ReleaseReason("MATCHING_EXPIRED")));
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM account_service." + table, Integer.class);
  }

  private static byte[] bytes(int value) {
    final byte[] result = new byte[32];
    Arrays.fill(result, (byte) value);
    return result;
  }

  @FunctionalInterface
  private interface EffectSink {
    void accept(MatchingAccountEffect effect);
  }
}
