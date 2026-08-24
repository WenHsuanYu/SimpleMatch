package com.simplematch.accountservice.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the Account critical-consumer status Interface used by operational adapters. */
class AccountFinalMatchingEventStatusTest {
  @Test
  void committedProgressExposesTheNextKafkaOffset() {
    final AccountFinalMatchingEventStatus status = new AccountFinalMatchingEventStatus();

    status.recordCommitted(0, 42L);

    assertThat(status.committedOffsets()).containsEntry(0, 43L);
  }
}
