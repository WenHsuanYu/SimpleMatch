package com.simplematch.quickfixgateway.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;

class FixSessionOwnershipTest {
  private static final SessionID SESSION_ID =
      new SessionID("FIX.4.4", "CLIENT1", "SIMPLEMATCH");

  @Test
  void oneOwnerMayClaimAndReleaseASessionButAConflictCannotProjectMessages() throws Exception {
    final FixSessionOwnership ownership = new FixSessionOwnership();
    assertThat(ownership.tryClaim(SESSION_ID, "owner-a")).isTrue();
    assertThat(ownership.tryClaim(SESSION_ID, "owner-a")).isTrue();
    assertThat(ownership.tryClaim(SESSION_ID, "owner-b")).isFalse();

    final InboundFixMessageHandler handler = mock(InboundFixMessageHandler.class);
    final QuickFixApplicationAdapter conflictingAdapter =
        new QuickFixApplicationAdapter(handler, ownership, "owner-b");
    conflictingAdapter.onLogon(SESSION_ID);
    conflictingAdapter.fromApp(new Message(), SESSION_ID);

    verifyNoInteractions(handler);
    ownership.release(SESSION_ID, "owner-a");
    assertThat(ownership.tryClaim(SESSION_ID, "owner-b")).isTrue();
  }
}
