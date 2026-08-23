package com.simplematch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.simplematch.contracts.matching.runtime.v1.MatchingEventIdentityV1;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the language-neutral version-one Matching identity contract to known answers. */
class MatchingEventIdentityV1Test {
  private static final String SESSION = "2026-08-11-regular";
  private static final UUID COMMAND_ID =
      UUID.fromString("0198a001-0000-7000-8000-000000000002");

  @Test
  void derivesThePublishedEventIdentityKnownAnswer() {
    assertEquals(
        "436c95c15c97744324aaaf0cfd6cd27b371839e944df9ae40ebab37a207cbb6f",
        HexFormat.of().formatHex(MatchingEventIdentityV1.eventId(SESSION, 0, COMMAND_ID, 0)));
  }

  @Test
  void derivesThePublishedTradeIdentityKnownAnswer() {
    assertEquals(
        "033ec379a4a4f1b3b6e5826b4a31731304662b0647e412e59b4abe21afc3241b",
        HexFormat.of().formatHex(MatchingEventIdentityV1.tradeId(SESSION, 0, COMMAND_ID, 0)));
  }
}
