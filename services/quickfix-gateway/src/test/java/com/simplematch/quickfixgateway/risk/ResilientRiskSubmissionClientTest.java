package com.simplematch.quickfixgateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilientRiskSubmissionClientTest {
  private static final NewOrderCommand NEW_ORDER =
      NewOrderCommand.newBuilder()
          .setCommandId("command-1")
          .setOrderId("0194a8f0-7c77-7b38-9e2d-2a5fdd0f7c15")
          .setClOrdId("C-1")
          .build();

  @DisplayName("temporary failures are retried with the same command until success")
  @Test
  void retriesTransientFailureWithSameCommandUntilSuccess() {
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger sleepCalls = new AtomicInteger();
    final RiskSubmissionClient delegate =
        new RiskSubmissionClient() {
          @Override
          public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
            attempts.incrementAndGet();
            if (attempts.get() == 1) {
              throw new StatusRuntimeException(Status.UNAVAILABLE);
            }
            return new RiskSubmissionResult(command.getOrderId(), true, "", "");
          }

          @Override
          public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
            throw new UnsupportedOperationException();
          }
        };

    final ResilientRiskSubmissionClient client =
        new ResilientRiskSubmissionClient(
            delegate,
            2,
            25,
            new RiskSubmissionCircuitBreaker(fixedClock(), 3, 1_000),
            delayMillis -> sleepCalls.incrementAndGet());

    final RiskSubmissionResult result = client.submitNewOrder(NEW_ORDER);

    assertThat(result.accepted()).isTrue();
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(sleepCalls.get()).isEqualTo(1);
  }

  @DisplayName("after the threshold, the circuit breaker fails fast until cooldown ends")
  @Test
  void opensCircuitAfterThresholdAndFailsFastUntilCooldownExpires() {
    final AtomicInteger attempts = new AtomicInteger();
    final MutableClock clock = new MutableClock(Instant.parse("2024-03-27T08:09:10Z"));
    final RiskSubmissionClient delegate =
        new RiskSubmissionClient() {
          @Override
          public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
            attempts.incrementAndGet();
            throw new StatusRuntimeException(Status.UNAVAILABLE);
          }

          @Override
          public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
            throw new UnsupportedOperationException();
          }
        };

    final ResilientRiskSubmissionClient client =
        new ResilientRiskSubmissionClient(
            delegate, 1, 0, new RiskSubmissionCircuitBreaker(clock, 2, 1_000), delayMillis -> {});

    assertThatThrownBy(() -> client.submitNewOrder(NEW_ORDER))
        .isInstanceOf(RiskSubmissionFailure.class)
        .hasMessageContaining("risk-service submit failed after 1 attempt");
    assertThatThrownBy(() -> client.submitNewOrder(NEW_ORDER))
        .isInstanceOf(RiskSubmissionFailure.class)
        .hasMessageContaining("risk-service submit failed after 1 attempt");

    assertThatThrownBy(() -> client.submitNewOrder(NEW_ORDER))
        .isInstanceOf(RiskSubmissionFailure.class)
        .extracting(error -> ((RiskSubmissionFailure) error).reasonCode())
        .isEqualTo("RISK_CIRCUIT_OPEN");
    assertThat(attempts.get()).isEqualTo(2);

    clock.advanceMillis(1_100);

    assertThatThrownBy(() -> client.submitNewOrder(NEW_ORDER))
        .isInstanceOf(RiskSubmissionFailure.class)
        .extracting(error -> ((RiskSubmissionFailure) error).reasonCode())
        .isEqualTo("RISK_UNAVAILABLE");
    assertThat(attempts.get()).isEqualTo(3);
  }

  private Clock fixedClock() {
    return Clock.fixed(Instant.parse("2024-03-27T08:09:10Z"), ZoneOffset.UTC);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    private void advanceMillis(long millis) {
      instant = instant.plusMillis(millis);
    }
  }
}
