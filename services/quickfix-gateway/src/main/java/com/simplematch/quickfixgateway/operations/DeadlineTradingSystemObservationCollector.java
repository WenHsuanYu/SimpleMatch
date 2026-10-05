package com.simplematch.quickfixgateway.operations;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Enforces one wall-clock deadline over a complete infrastructure observation attempt. */
public final class DeadlineTradingSystemObservationCollector
    implements TradingSystemObservationCollector {
  private final TradingSystemObservationCollector delegate;
  private final Executor executor;
  private final Duration deadline;

  /** Creates the fail-closed total-deadline decorator. */
  public DeadlineTradingSystemObservationCollector(
      TradingSystemObservationCollector delegate, Executor executor, Duration deadline) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.deadline = Objects.requireNonNull(deadline, "deadline");
  }

  @Override
  public TradingSystemObservation collect() {
    final CompletableFuture<TradingSystemObservation> pending =
        CompletableFuture.supplyAsync(delegate::collect, executor);
    try {
      return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("live observation interrupted", failure);
    } catch (ExecutionException failure) {
      final Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw new IllegalStateException("live observation failed", cause);
    } catch (TimeoutException failure) {
      pending.cancel(true);
      throw new IllegalStateException("live observation exceeded its total deadline", failure);
    }
  }
}
