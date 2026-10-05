package com.simplematch.quickfixgateway.operations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeadlineTradingSystemObservationCollectorTest {
  @Test
  void rejectsAnObservationThatCompletesAfterTheWholeCollectionDeadline() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final TradingSystemObservationCollector slowSource =
          () -> {
            try {
              release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
            return null;
          };
      final var collector =
          new DeadlineTradingSystemObservationCollector(
              slowSource, executor, Duration.ofMillis(20));

      assertThatThrownBy(collector::collect)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("total deadline");
      release.countDown();
    }
  }
}
