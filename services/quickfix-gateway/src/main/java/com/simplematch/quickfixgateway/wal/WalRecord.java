package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.common.v2.TimeInForce;
import java.util.Objects;

/** Represents one semantic, gateway-local durable inbound FIX command. */
public record WalRecord(
    WalMetadata metadata,
    FixSessionIdentity session,
    WalOrderReference orderReference,
    WalCommand command,
    RawFixMessage rawFixMessage) {
  /** Requires all semantic WAL components. */
  public WalRecord {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(orderReference, "orderReference");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(rawFixMessage, "rawFixMessage");
    if (command instanceof WalCommand.NewOrder && !orderReference.origClOrdId().isEmpty()) {
      throw new IllegalArgumentException("new order must not have orig_cl_ord_id");
    }
    if (command instanceof WalCommand.Cancel && orderReference.origClOrdId().isEmpty()) {
      throw new IllegalArgumentException("cancel must have orig_cl_ord_id");
    }
  }

  /** Returns the persisted schema version. */
  public String schemaVersion() {
    return metadata.schemaVersion();
  }

  /** Returns the durable record identity. */
  public String recordId() {
    return metadata.recordId();
  }

  /** Returns the persistence timestamp. */
  public long createdAtUnixMs() {
    return metadata.createdAtUnixMs();
  }

  /** Returns the gateway source identity. */
  public String sourceService() {
    return metadata.sourceService();
  }

  /** Returns the original FIX text retained by the WAL. */
  public String rawFix() {
    return rawFixMessage.value();
  }

  /** Returns the FIX sender CompID. */
  public String senderCompId() {
    return session.senderCompId();
  }

  /** Returns the FIX target CompID. */
  public String targetCompId() {
    return session.targetCompId();
  }

  /** Returns the FIX message type represented by the command variant. */
  public String messageType() {
    return command.messageType();
  }

  /** Returns the durable FIX-facing order identity. */
  public String orderId() {
    return orderReference.orderId();
  }

  /** Returns the current client order identity. */
  public String clOrdId() {
    return orderReference.clOrdId();
  }

  /** Returns the original client order identity for a cancellation. */
  public String origClOrdId() {
    return orderReference.origClOrdId();
  }

  /** Returns the canonical account identity. */
  public String accountId() {
    return orderReference.accountId();
  }

  /** Returns new-order terms, or fails when this record is a cancellation. */
  public WalOrderTerms newOrderTerms() {
    if (command instanceof WalCommand.NewOrder newOrder) {
      return newOrder.terms();
    }
    throw new IllegalStateException("cancellation has no order terms");
  }

  /** Returns the instrument symbol carried by this command. */
  public String symbol() {
    return switch (command) {
      case WalCommand.NewOrder newOrder -> newOrder.terms().symbol();
      case WalCommand.Cancel cancel -> cancel.symbol();
    };
  }

  /** Returns the side carried by this command. */
  public Side side() {
    return switch (command) {
      case WalCommand.NewOrder newOrder -> newOrder.terms().side();
      case WalCommand.Cancel cancel -> cancel.side();
    };
  }

  /** Returns the new-order quantity or the cancellation placeholder. */
  public String quantity() {
    return command instanceof WalCommand.NewOrder newOrder ? newOrder.terms().quantity() : "";
  }

  /** Returns the new-order price or the cancellation placeholder. */
  public String price() {
    return command instanceof WalCommand.NewOrder newOrder ? newOrder.terms().price() : "";
  }

  /** Returns the new-order type or the cancellation placeholder. */
  public OrderType orderType() {
    return command instanceof WalCommand.NewOrder newOrder
        ? newOrder.terms().orderType()
        : OrderType.ORDER_TYPE_UNSPECIFIED;
  }

  /** Returns the new-order time-in-force or the cancellation placeholder. */
  public TimeInForce tif() {
    return command instanceof WalCommand.NewOrder newOrder
        ? newOrder.terms().tif()
        : TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
  }

  /** Returns the local command classification. */
  public WalCommand.Type commandType() {
    return command.commandType();
  }

  /** Returns whether two records describe the same client intent and normalized command content. */
  public boolean hasSameBusinessIntent(WalRecord other) {
    Objects.requireNonNull(other, "other");
    return session.equals(other.session)
        && orderReference.equals(other.orderReference)
        && command.equals(other.command);
  }
}
