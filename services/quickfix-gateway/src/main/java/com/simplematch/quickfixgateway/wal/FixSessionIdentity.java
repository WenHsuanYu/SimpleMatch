package com.simplematch.quickfixgateway.wal;

/** FIX session identities carried by a durable inbound command. */
public record FixSessionIdentity(String senderCompId, String targetCompId) {
  /** Requires both FIX session identities. */
  public FixSessionIdentity {
    senderCompId = WalValidation.requiredText(senderCompId, "sender_comp_id");
    targetCompId = WalValidation.requiredText(targetCompId, "target_comp_id");
  }
}
