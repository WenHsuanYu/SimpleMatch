package com.simplematch.contracts.v2;

import java.util.UUID;

/** Strongly typed UUID-backed identifiers used by v2 domain contracts. */
public final class V2Identifiers {
  private V2Identifiers() {}

  /** Identifies one emitted event. */
  public record EventId(UUID value) {
    /** Creates a validated event identifier. */
    public EventId {
      value = required(value, "event_id");
    }

    /** Parses a UUID event identifier. */
    public static EventId parse(String raw) {
      return new EventId(parseUuid(raw, "event_id"));
    }
  }

  /** Identifies one command operation. */
  public record CommandId(UUID value) {
    /** Creates a validated command identifier. */
    public CommandId {
      value = required(value, "command_id");
    }

    /** Parses a UUID command identifier. */
    public static CommandId parse(String raw) {
      return new CommandId(parseUuid(raw, "command_id"));
    }
  }

  /** Identifies an order. */
  public record OrderId(UUID value) {
    /** Creates a validated order identifier. */
    public OrderId {
      value = required(value, "order_id");
    }

    /** Parses a UUID order identifier. */
    public static OrderId parse(String raw) {
      return new OrderId(parseUuid(raw, "order_id"));
    }
  }

  /** Identifies an account. */
  public record AccountId(UUID value) {
    /** Creates a validated account identifier. */
    public AccountId {
      value = required(value, "account_id");
    }

    /** Parses a UUID account identifier. */
    public static AccountId parse(String raw) {
      return new AccountId(parseUuid(raw, "account_id"));
    }
  }

  /** Identifies a reservation. */
  public record ReservationId(UUID value) {
    /** Creates a validated reservation identifier. */
    public ReservationId {
      value = required(value, "reservation_id");
    }

    /** Parses a UUID reservation identifier. */
    public static ReservationId parse(String raw) {
      return new ReservationId(parseUuid(raw, "reservation_id"));
    }
  }

  /** Identifies an execution. */
  public record ExecutionId(UUID value) {
    /** Creates a validated execution identifier. */
    public ExecutionId {
      value = required(value, "execution_id");
    }

    /** Parses a UUID execution identifier. */
    public static ExecutionId parse(String raw) {
      return new ExecutionId(parseUuid(raw, "execution_id"));
    }
  }

  /** Identifies an immutable routing or market snapshot. */
  public record SnapshotId(UUID value) {
    /** Creates a validated snapshot identifier. */
    public SnapshotId {
      value = required(value, "snapshot_id");
    }

    /** Parses a UUID snapshot identifier. */
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
