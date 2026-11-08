package com.simplematch.tools.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exercises the exact ordering of bounded Matching stops around fault injection. */
class KubernetesCriticalDeliveryPausesTest {
  @Test
  void rejectedFixAndDatabaseOutageUseIndependentMatchingPauseBudgets() throws Exception {
    final var clock = new AtomicInteger();
    final var stopCount = new AtomicInteger();
    final var resumedCount = new AtomicInteger();

    final String result = KubernetesCriticalDeliveryPauses.run(
        () -> {
          stopCount.incrementAndGet();
          return new SimulatedStop(clock, resumedCount);
        },
        paused -> {
          assertEquals(0, resumedCount.get());
          paused.consume(7); // Gateway pause and rejected FIX receipt
        },
        () -> {
          assertEquals(1, resumedCount.get(), "Matching must resume before durable SQL observation");
          clock.addAndGet(4); // Durable rejection check with the connector still paused
        },
        paused -> {
          paused.consume(7); // Connect offset, PostgreSQL scale/delete and no-Pod oracle
          assertEquals(1, resumedCount.get(),
              "Matching must be resumed before the second independent fault window");
          return "postgres-absent";
        });

    assertEquals("postgres-absent", result);
    assertEquals(2, stopCount.get());
    assertEquals(2, resumedCount.get());
  }

  @Test
  void rejectionFailureRestoresMatchingWithoutReleasingTheDatabaseFault() {
    final var clock = new AtomicInteger();
    final var stopped = new AtomicInteger();
    final var resumed = new AtomicInteger();

    assertThrows(IllegalStateException.class, () -> KubernetesCriticalDeliveryPauses.run(
        () -> {
          stopped.incrementAndGet();
          return new SimulatedStop(clock, resumed);
        },
        ignored -> { throw new IllegalStateException("FIX rejection failed"); },
        () -> { throw new AssertionError("durable SQL must not run after rejection failure"); },
        ignored -> { throw new AssertionError("outage must not start after rejection failure"); }));

    assertEquals(1, stopped.get());
    assertEquals(1, resumed.get());
  }

  @Test
  void expiredDatabaseOutageBudgetRestoresTheSecondMatchingPause() {
    final var clock = new AtomicInteger();
    final var stopped = new AtomicInteger();
    final var resumed = new AtomicInteger();

    assertThrows(TimeoutException.class, () -> KubernetesCriticalDeliveryPauses.run(
        () -> {
          stopped.incrementAndGet();
          return new SimulatedStop(clock, resumed);
        },
        pause -> pause.consume(3),
        () -> assertEquals(1, resumed.get()),
        pause -> {
          pause.consume(11);
          return "unreachable";
        }));

    assertEquals(2, stopped.get());
    assertEquals(2, resumed.get());
  }

  private static final class SimulatedStop implements AutoCloseable {
    private final AtomicInteger clock;
    private final AtomicInteger resumed;
    private final int expiresAt;

    private SimulatedStop(AtomicInteger clock, AtomicInteger resumed) {
      this.clock = clock;
      this.resumed = resumed;
      expiresAt = clock.get() + 10;
    }

    private void consume(int ticks) throws TimeoutException {
      if (clock.addAndGet(ticks) > expiresAt) {
        throw new TimeoutException("Matching pause budget expired before PostgreSQL absence");
      }
    }

    @Override
    public void close() {
      resumed.incrementAndGet();
    }
  }
}
