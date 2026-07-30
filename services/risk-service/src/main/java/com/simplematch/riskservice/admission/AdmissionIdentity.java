package com.simplematch.riskservice.admission;

import java.util.Objects;
import java.util.UUID;

/**
 * Command, order, and account identities for one admission attempt.
 *
 * @param commandId the unique command identity
 * @param orderId the affected order identity
 * @param accountId the owning account identity
 */
public record AdmissionIdentity(CommandId commandId, OrderId orderId, AccountId accountId) {
  /** Requires all typed identities. */
  public AdmissionIdentity {
    commandId = Objects.requireNonNull(commandId, "commandId");
    orderId = Objects.requireNonNull(orderId, "orderId");
    accountId = Objects.requireNonNull(accountId, "accountId");
  }

  /** Unique identity of one admission command. */
  public record CommandId(UUID value) {
    /** Requires a command identifier. */
    public CommandId {
      value = Objects.requireNonNull(value, "command_id");
    }
  }

  /** Unique identity of the affected order. */
  public record OrderId(UUID value) {
    /** Requires an order identifier. */
    public OrderId {
      value = Objects.requireNonNull(value, "order_id");
    }
  }

  /** Unique identity of the account owning the order. */
  public record AccountId(UUID value) {
    /** Requires an account identifier. */
    public AccountId {
      value = Objects.requireNonNull(value, "account_id");
    }
  }
}
