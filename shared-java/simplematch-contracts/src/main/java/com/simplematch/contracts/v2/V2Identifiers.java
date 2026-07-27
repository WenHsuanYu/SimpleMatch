package com.simplematch.contracts.v2;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed UUID-backed identifiers used by v2 domain contracts. */
public final class V2Identifiers {
  private V2Identifiers() {}

  /** Identifies one emitted event. */
  public record EventId(UUID value) {
    public EventId {
      value = required(value, "event_id");
    }

    public static EventId parse(String raw) {
      return new EventId(parseUuid(raw, "event_id"));
    }
  }

  /** Identifies one command operation. */
  public record CommandId(UUID value) {
    public CommandId {
      value = required(value, "command_id");
    }

    public static CommandId parse(String raw) {
      return new CommandId(parseUuid(raw, "command_id"));
    }
  }

  /** Identifies an order. */
  public record OrderId(UUID value) {
    public OrderId {
      value = required(value, "order_id");
    }

    public static OrderId parse(String raw) {
      return new OrderId(parseUuid(raw, "order_id"));
    }
  }

  /** Identifies an account. */
  public record AccountId(UUID value) {
    public AccountId {
      value = required(value, "account_id");
    }

    public static AccountId parse(String raw) {
      return new AccountId(parseUuid(raw, "account_id"));
    }
  }

  /** Identifies a reservation. */
  public record ReservationId(UUID value) {
    public ReservationId {
      value = required(value, "reservation_id");
    }

    public static ReservationId parse(String raw) {
      return new ReservationId(parseUuid(raw, "reservation_id"));
    }
  }

  /** Identifies an execution. */
  public record ExecutionId(UUID value) {
    public ExecutionId {
      value = required(value, "execution_id");
    }

    public static ExecutionId parse(String raw) {
      return new ExecutionId(parseUuid(raw, "execution_id"));
    }
  }

  /** Identifies an immutable routing or market snapshot. */
  public record SnapshotId(UUID value) {
    public SnapshotId {
      value = required(value, "snapshot_id");
    }

    public static SnapshotId parse(String raw) {
      return new SnapshotId(parseUuid(raw, "snapshot_id"));
    }
  }

  private static UUID required(UUID value, String fieldName) {
    if (value == null) {
      throw new DomainValidationException(fieldName + " is required");
    }
    return value;
  }

  private static UUID parseUuid(String raw, String fieldName) {
    if (raw == null || raw.isBlank()) {
      throw new DomainValidationException(fieldName + " is required");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException exception) {
      throw new DomainValidationException(fieldName + " must be a UUID");
    }
  }
}
