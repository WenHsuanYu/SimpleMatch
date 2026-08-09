package com.simplematch.accountservice.authority;

import java.util.Objects;
import java.util.UUID;

/** Canonical account identity owned by the Account domain. */
public record AccountId(UUID value) {
  /** Requires a concrete UUID identity. */
  public AccountId {
    Objects.requireNonNull(value, "value");
  }

  /** Parses the canonical UUID representation used at service boundaries. */
  public static AccountId parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("account_id is required");
    }
    try {
      return new AccountId(UUID.fromString(raw));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("account_id must be a UUID", invalid);
    }
  }

  /** Returns the canonical string representation used by protobuf and Kafka contracts. */
  public String wireValue() {
    return value.toString();
  }
}
