package com.simplematch.riskservice.testsupport;

/**
 * Normalizes legacy symbolic test command identifiers into UUID-shaped values.
 */
public final class TestCommandIds {
  private TestCommandIds() {
  }

  /**
   * Returns a UUID-shaped command id for legacy symbolic fixtures and leaves other inputs intact.
   *
   * @param commandId the symbolic or already-normalized command id
   * @return a UUID string when the input is a known legacy fixture id; otherwise the original input
   */
  public static String normalize(String commandId) {
    return switch (commandId) {
      case "cmd-1" -> "11111111-1111-7111-8111-111111111111";
      case "cmd-2" -> "22222222-2222-7222-8222-222222222222";
      case "cmd-3" -> "33333333-3333-7333-8333-333333333333";
      case "cmd-4" -> "44444444-4444-7444-8444-444444444444";
      case "cmd-5" -> "55555555-5555-7555-8555-555555555555";
      case "cmd-delayed" -> "66666666-6666-7666-8666-666666666666";
      case "cmd-winner" -> "77777777-7777-7777-8777-777777777777";
      case "cmd-plain" -> "88888888-8888-7888-8888-888888888888";
      case "cmd-surrogate" -> "99999999-9999-7999-8999-999999999999";
      case "cmd-existing" -> "aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa";
      case "cmd-loser" -> "bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb";
      default -> commandId;
    };
  }
}
