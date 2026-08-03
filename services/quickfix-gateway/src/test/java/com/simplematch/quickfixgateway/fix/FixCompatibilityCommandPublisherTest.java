package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.quickfixgateway.kafka.OrdersCommandPublisher;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixCompatibilityCommandPublisherTest {
  @DisplayName("publishes compatibility commands without waiting for completion")
  @Test
  void publishesWithoutWaitingForCompletion() {
    final CompletableFuture<Void> pendingPublication = new CompletableFuture<>();
    final OrderCommand command =
        OrderCommand.newBuilder().setCommandId("command-1").build();
    final RecordingPublisher publisher = new RecordingPublisher(pendingPublication);

    final CompletableFuture<Void> observedPublication =
        new FixCompatibilityCommandPublisher(publisher).publish(command);

    assertThat(publisher.publishedCommand).isSameAs(command);
    assertThat(observedPublication).isNotCompleted();

    pendingPublication.complete(null);

    assertThat(observedPublication.isDone()).isTrue();
    assertThat(observedPublication.isCompletedExceptionally()).isFalse();
  }

  private static final class RecordingPublisher implements OrdersCommandPublisher {
    private final CompletableFuture<Void> publication;
    private OrderCommand publishedCommand;

    private RecordingPublisher(CompletableFuture<Void> publication) {
      this.publication = publication;
    }

    /**
     * Records the command and returns the caller-controlled publication stage.
     *
     * @param command the command to record
     * @return the caller-controlled publication stage
     */
    @Override
    public CompletableFuture<Void> publish(OrderCommand command) {
      publishedCommand = command;
      return publication;
    }
  }
}
