package com.simplematch.quickfixgateway.kafka;

import com.simplematch.config.delivery.DeliveryRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Schedules non-critical projection retries on a bounded local executor. */
public final class ScheduledNonCriticalRetryScheduler
    implements NonCriticalRetryScheduler, AutoCloseable {
  private final ScheduledExecutorService executor;
  private final Set<ScheduledFuture<?>> scheduledRetries = ConcurrentHashMap.newKeySet();

  /** Creates one daemon retry worker owned by the QuickFIX gateway. */
  public ScheduledNonCriticalRetryScheduler() {
    final ThreadFactory threadFactory =
        runnable -> {
          final Thread thread = new Thread(runnable, "quickfix-projection-retry");
          thread.setDaemon(true);
          return thread;
        };
    executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
  }

  @Override
  public void schedule(DeliveryRecord record, Instant retryAt, Runnable retry) {
    Objects.requireNonNull(record, "delivery record");
    Objects.requireNonNull(retryAt, "retry time");
    Objects.requireNonNull(retry, "retry action");
    final long delayMillis =
        Math.max(0L, Duration.between(Instant.now(), retryAt).toMillis());
    final AtomicReference<ScheduledFuture<?>> scheduledReference = new AtomicReference<>();
    final ScheduledFuture<?> scheduled =
        executor.schedule(
            () -> {
              try {
                retry.run();
              } finally {
                final ScheduledFuture<?> current = scheduledReference.get();
                if (current != null) {
                  scheduledRetries.remove(current);
                }
              }
            },
            delayMillis,
            TimeUnit.MILLISECONDS);
    scheduledReference.set(scheduled);
    scheduledRetries.add(scheduled);
    if (scheduled.isDone()) {
      scheduledRetries.remove(scheduled);
    }
  }

  /** Stops retries when the gateway shuts down. */
  @Override
  public void close() {
    scheduledRetries.forEach(future -> future.cancel(false));
    scheduledRetries.clear();
    executor.shutdownNow();
  }
}
