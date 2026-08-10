package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import io.grpc.Status;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/** Adds bounded retry and circuit-breaking behavior around synchronous Risk admission. */
public final class ResilientRiskSubmissionClient implements RiskSubmissionClient {
  @FunctionalInterface
  interface Sleeper {
    void sleep(long delayMillis);
  }

  private static final Set<Status.Code> RETRYABLE_CODES =
      EnumSet.of(
          Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED);

  private final RiskSubmissionClient delegate;
  private final int maxAttempts;
  private final long backoffMillis;
  private final RiskSubmissionCircuitBreaker circuitBreaker;
  private final Sleeper sleeper;

  /** Creates a client with bounded retry and circuit-breaker settings. */
  public ResilientRiskSubmissionClient(
      RiskSubmissionClient delegate,
      int maxAttempts,
      long backoffMillis,
      int consecutiveFailureThreshold,
      long breakerOpenDurationMillis,
      Clock clock) {
    this(
        delegate,
        maxAttempts,
        backoffMillis,
        new RiskSubmissionCircuitBreaker(
            clock, consecutiveFailureThreshold, breakerOpenDurationMillis),
        delayMillis -> {
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw RiskSubmissionFailure.interrupted(interruptedException);
          }
        });
  }

  ResilientRiskSubmissionClient(
      RiskSubmissionClient delegate,
      int maxAttempts,
      long backoffMillis,
      RiskSubmissionCircuitBreaker circuitBreaker,
      Sleeper sleeper) {
    this.delegate = delegate;
    this.maxAttempts = maxAttempts;
    this.backoffMillis = backoffMillis;
    this.circuitBreaker = circuitBreaker;
    this.sleeper = sleeper;
  }

  @Override
  public RiskSubmissionResult submitNewOrder(NewOrderCommand command) {
    return execute("submit", () -> delegate.submitNewOrder(command));
  }

  @Override
  public RiskSubmissionResult submitCancel(CancelOrderCommand command) {
    return execute("cancel", () -> delegate.submitCancel(command));
  }

  private RiskSubmissionResult execute(
      String operation, Supplier<RiskSubmissionResult> call) {
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt += 1) {
      circuitBreaker.acquirePermission();
      try {
        final RiskSubmissionResult result = call.get();
        circuitBreaker.recordSuccess();
        return result;
      } catch (RuntimeException exception) {
        circuitBreaker.recordFailure();
        lastFailure = exception;
        if (!isRetryable(exception) || attempt >= maxAttempts) {
          throw RiskSubmissionFailure.unavailable(operation, attempt, exception);
        }
        if (backoffMillis > 0) {
          sleeper.sleep(backoffMillis);
        }
      }
    }
    throw RiskSubmissionFailure.unavailable(operation, maxAttempts, lastFailure);
  }

  private boolean isRetryable(RuntimeException exception) {
    return RETRYABLE_CODES.contains(Status.fromThrowable(exception).getCode());
  }
}
