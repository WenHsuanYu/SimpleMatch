package com.simplematch.accountservice.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountLifecycleOutboxTest {
  @Test
  void payloadIsOwnedByTheOutboxBoundary() {
    final byte[] input = new byte[] {1, 2, 3};
    final AccountLifecycleOutbox outbox =
        new AccountLifecycleOutbox(
            UUID.randomUUID(),
            "account.lifecycle",
            "order-1",
            input,
            "event.v1",
            "{}",
            "reservation",
            "reservation-1",
            100L);

    input[0] = 9;
    final byte[] exposedPayload = outbox.payload();
    exposedPayload[1] = 8;

    assertThat(outbox.payload()).containsExactly(1, 2, 3);
  }
}
