package com.simplematch.quickfixgateway.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilientRiskSubmissionClientTest {
  private static final OrderCommand NEW_ORDER = OrderCommand.newBuilder()
      .setCommandType(CommandType.COMMAND_TYPE_NEW)
      .setOrderId("O-1")
      .setClientOrderId("C-1")
      .build();

  // 驗證暫時性風控故障時，client 會用相同命令重試直到成功。
  // 情境：第一次回傳 UNAVAILABLE、第二次成功，確認重試次數與 sleep 次數都符合預期。
  @DisplayName("暫時性故障時會以同一指令重試直到成功")
  @Test
  void retriesTransientFailureWithSameCommandUntilSuccess() {
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger sleepCalls = new AtomicInteger();
    final RiskSubmissionClient delegate = new RiskSubmissionClient() {
      @Override
      public RiskSubmissionResult submitNewOrder(OrderCommand command) {
        attempts.incrementAndGet();
        if (attempts.get() == 1) {
          throw new StatusRuntimeException(Status.UNAVAILABLE);
        }
        return new RiskSubmissionResult(command.getOrderId(), true, "", "");
      }

      @Override
      public RiskSubmissionResult submitCancel(OrderCommand command) {
        throw new UnsupportedOperationException();
      }
    };

    final ResilientRiskSubmissionClient client = new ResilientRiskSubmissionClient(
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

  // 驗證連續失敗達門檻後，斷路器會打開並在冷卻期內快速失敗。
  // 情境：連續觸發 UNAVAILABLE 直到斷路器開啟，再推進時鐘確認冷卻後重新嘗試。
  @DisplayName("達到門檻後斷路器會快速失敗直到冷卻結束")
  @Test
  void opensCircuitAfterThresholdAndFailsFastUntilCooldownExpires() {
    final AtomicInteger attempts = new AtomicInteger();
    final MutableClock clock = new MutableClock(Instant.parse("2024-03-27T08:09:10Z"));
    final RiskSubmissionClient delegate = new RiskSubmissionClient() {
      @Override
      public RiskSubmissionResult submitNewOrder(OrderCommand command) {
        attempts.incrementAndGet();
        throw new StatusRuntimeException(Status.UNAVAILABLE);
      }

      @Override
      public RiskSubmissionResult submitCancel(OrderCommand command) {
        throw new UnsupportedOperationException();
      }
    };

    final ResilientRiskSubmissionClient client = new ResilientRiskSubmissionClient(
        delegate,
        1,
        0,
        new RiskSubmissionCircuitBreaker(clock, 2, 1_000),
        delayMillis -> { });

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