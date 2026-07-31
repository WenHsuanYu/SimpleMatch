package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountLifecycleOutboxTest {
  @DisplayName("outbox owns its semantic payload bytes at both construction and read boundaries")
  @Test
  void payloadIsOwnedByTheOutboxBoundary() {
    final byte[] input = new byte[] {1, 2, 3};
    final AccountLifecycleOutbox outbox =
        new AccountLifecycleOutbox(
            new AccountLifecycleOutbox.EventIdentity(UUID.randomUUID()),
            new AccountLifecycleOutbox.Destination("account.lifecycle", "order-1"),
            new AccountLifecycleOutbox.Payload(input, "event.v1", "{}"),
            new AccountLifecycleOutbox.AggregateReference("reservation", "reservation-1"),
            100L);

    input[0] = 9;
    final byte[] exposedPayload = outbox.payload().bytes();
    exposedPayload[1] = 8;

    assertThat(outbox.payload().bytes()).containsExactly(1, 2, 3);
  }

  @DisplayName("outbox exposes event, destination, payload, aggregate, and time as semantic groups")
  @Test
  void exposesSemanticGroups() {
    final UUID eventId = UUID.randomUUID();
    final AccountLifecycleOutbox outbox =
        new AccountLifecycleOutbox(
            new AccountLifecycleOutbox.EventIdentity(eventId),
            new AccountLifecycleOutbox.Destination("account.lifecycle", "order-1"),
            new AccountLifecycleOutbox.Payload(new byte[] {1}, "event.v1", "{}"),
            new AccountLifecycleOutbox.AggregateReference("reservation", "reservation-1"),
            100L);

    assertThat(outbox.eventIdentity().eventId()).isEqualTo(eventId);
    assertThat(outbox.destination().topic()).isEqualTo("account.lifecycle");
    assertThat(outbox.destination().messageKey()).isEqualTo("order-1");
    assertThat(outbox.payload().payloadType()).isEqualTo("event.v1");
    assertThat(outbox.payload().headersJson()).isEqualTo("{}");
    assertThat(outbox.aggregateReference().aggregateType()).isEqualTo("reservation");
    assertThat(outbox.aggregateReference().aggregateId()).isEqualTo("reservation-1");
    assertThat(outbox.createdAtUnixMs()).isEqualTo(100L);
  }

  @DisplayName("outbox rejects invalid semantic group values")
  @Test
  void rejectsInvalidValues() {
    assertThatThrownBy(
            () ->
                new AccountLifecycleOutbox.Payload(
                    new byte[0], "event.v1", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload must not be empty");
    assertThatThrownBy(
            () -> new AccountLifecycleOutbox.Destination(" ", "order-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("topic must not be blank");
    assertThatThrownBy(
            () -> new AccountLifecycleOutbox.AggregateReference("reservation", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("aggregate_id must not be blank");
    assertThatThrownBy(
            () ->
                new AccountLifecycleOutbox(
                    new AccountLifecycleOutbox.EventIdentity(UUID.randomUUID()),
                    new AccountLifecycleOutbox.Destination("account.lifecycle", "order-1"),
                    new AccountLifecycleOutbox.Payload(new byte[] {1}, "event.v1", "{}"),
                    new AccountLifecycleOutbox.AggregateReference(
                        "reservation", "reservation-1"),
                    -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("created_at_unix_ms must be non-negative");
  }
}
