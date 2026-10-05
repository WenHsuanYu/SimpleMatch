package com.simplematch.quickfixgateway.operations.live;

import com.simplematch.quickfixgateway.operations.KafkaStatus;
import com.simplematch.quickfixgateway.operations.MatchingFleetStatus;
import com.simplematch.quickfixgateway.operations.OperationalComponentState;
import com.simplematch.quickfixgateway.operations.RiskStatus;
import com.simplematch.quickfixgateway.operations.TradingSystemObservation;
import com.simplematch.quickfixgateway.operations.TradingSystemObservationCollector;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Complete production collector over HTTP service facts and Kafka control-plane progress. */
public final class LiveTradingSystemObservationCollector
    implements TradingSystemObservationCollector {
  private final GatewayLiveStatusClient statusClient;
  private final KafkaAdmissionClient kafkaClient;
  private final Executor executor;

  /** Creates the collector whose all-or-nothing result is safe to publish to admission policy. */
  public LiveTradingSystemObservationCollector(
      GatewayLiveStatusClient statusClient, KafkaAdmissionClient kafkaClient, Executor executor) {
    this.statusClient = Objects.requireNonNull(statusClient, "statusClient");
    this.kafkaClient = Objects.requireNonNull(kafkaClient, "kafkaClient");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public TradingSystemObservation collect() {
    final CompletableFuture<RiskStatus> riskPending =
        CompletableFuture.supplyAsync(statusClient::riskStatus, executor);
    final CompletableFuture<KafkaAdmissionSnapshot> kafkaPending =
        CompletableFuture.supplyAsync(kafkaClient::observe, executor);
    final RiskStatus risk = join(riskPending);
    final KafkaAdmissionSnapshot kafka = join(kafkaPending);
    final CompletableFuture<MatchingFleetStatus> matchingPending =
        CompletableFuture.supplyAsync(
            () -> statusClient.matchingFleet(kafka.commandEndOffsets()), executor);
    final var consumersPending =
        CompletableFuture.supplyAsync(
            () -> statusClient.criticalConsumers(risk.identity(), kafka), executor);
    final MatchingFleetStatus matching = join(matchingPending);
    final KafkaStatus kafkaStatus =
        new KafkaStatus(
            OperationalComponentState.READY,
            risk.identity(),
            kafka.commandPartitionCount(),
            kafka.eventPartitionCount(),
            false,
            kafka.observedAt(),
            "READY");
    return new TradingSystemObservation(
        risk,
        matching,
        join(consumersPending),
        kafkaStatus);
  }

  private static <T> T join(CompletableFuture<T> pending) {
    try {
      return pending.join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw failure;
    }
  }
}
