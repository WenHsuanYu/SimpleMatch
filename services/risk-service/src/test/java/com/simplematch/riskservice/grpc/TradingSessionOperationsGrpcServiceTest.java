package com.simplematch.riskservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.risk.v2.CloseTradingSessionRequest;
import com.simplematch.contracts.risk.v2.CloseTradingSessionResponse;
import com.simplematch.marketreference.MarketReferenceArtifactStartupValidator;
import com.simplematch.marketreference.VerifiedMarketReferenceArtifact;
import com.simplematch.riskservice.admission.MatchingBarrierOutboxFactory;
import com.simplematch.riskservice.admission.TradingSessionBarrierService;
import com.simplematch.riskservice.outbox.OutboxRecord;
import com.simplematch.riskservice.outbox.OutboxRepository;
import io.grpc.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class TradingSessionOperationsGrpcServiceTest {
  @Test
  void repeatedCloseRequestsReuseTheSameDurableBarrierSet() {
    final RecordingOutboxRepository outbox = new RecordingOutboxRepository();
    final TradingSessionOperationsGrpcService service = service(outbox);
    final CloseTradingSessionRequest request =
        CloseTradingSessionRequest.newBuilder()
            .setTradingSessionId("2026-08-11-regular")
            .build();

    final TestStreamObserver<CloseTradingSessionResponse> first = new TestStreamObserver<>();
    service.closeTradingSession(request, first);
    final TestStreamObserver<CloseTradingSessionResponse> repeated = new TestStreamObserver<>();
    service.closeTradingSession(request, repeated);

    assertThat(first.completed()).isTrue();
    assertThat(first.error()).isNull();
    assertThat(first.value().getNewlyInsertedBarriers()).isEqualTo(15);
    assertThat(repeated.completed()).isTrue();
    assertThat(repeated.error()).isNull();
    assertThat(repeated.value().getNewlyInsertedBarriers()).isZero();
    assertThat(outbox.eventIds()).hasSize(15);
  }

  @Test
  void rejectsTradingSessionThatDoesNotMatchTheVerifiedArtifactDay() {
    final TradingSessionOperationsGrpcService service = service(new RecordingOutboxRepository());
    final TestStreamObserver<CloseTradingSessionResponse> observer = new TestStreamObserver<>();

    service.closeTradingSession(
        CloseTradingSessionRequest.newBuilder()
            .setTradingSessionId("2026-08-12-regular")
            .build(),
        observer);

    assertThat(observer.completed()).isFalse();
    assertThat(Status.fromThrowable(observer.error()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  private static TradingSessionOperationsGrpcService service(OutboxRepository outbox) {
    final MatchingBarrierOutboxFactory factory =
        new MatchingBarrierOutboxFactory(
            "matching.commands",
            artifact(),
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            Clock.fixed(Instant.ofEpochMilli(100L), ZoneOffset.UTC));
    final DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:trading-session-close;DB_CLOSE_DELAY=-1");
    final TradingSessionBarrierService barriers =
        new TradingSessionBarrierService(
            factory,
            outbox,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    return new TradingSessionOperationsGrpcService(barriers);
  }

  private static VerifiedMarketReferenceArtifact artifact() {
    try {
      return new MarketReferenceArtifactStartupValidator(new ObjectMapper())
          .validate(
              resource("/market-reference/market_reference.json"),
              new String(
                      resource("/market-reference/market_reference.sha256"),
                      StandardCharsets.US_ASCII)
                  .trim(),
              LocalDate.of(2026, 8, 11));
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static byte[] resource(String path) throws IOException {
    try (var stream = TradingSessionOperationsGrpcServiceTest.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IOException("missing test resource: " + path);
      }
      return stream.readAllBytes();
    }
  }

  private static final class RecordingOutboxRepository implements OutboxRepository {
    private final Set<String> eventIds = new HashSet<>();

    @Override
    public void insert(OutboxRecord record) {
      eventIds.add(record.eventInfo().eventId());
    }

    @Override
    public boolean insertIfAbsent(OutboxRecord record) {
      return eventIds.add(record.eventInfo().eventId());
    }

    Set<String> eventIds() {
      return Set.copyOf(eventIds);
    }
  }
}
