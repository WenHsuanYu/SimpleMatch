package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v2.Side;

/** Command-specific facts carried by one durable inbound FIX command. */
public sealed interface WalCommand permits WalCommand.NewOrder, WalCommand.Cancel {
  /** FIX message type represented by this command. */
  String messageType();

  /** Gateway command classification represented by this command. */
  Type commandType();

  /** Stable WAL command names retained independently of transport contract versions. */
  enum Type {
    COMMAND_TYPE_NEW,
    COMMAND_TYPE_CANCEL
  }

  /** A new order with complete order terms. */
  record NewOrder(WalOrderTerms terms) implements WalCommand {
    /** Requires complete new-order terms. */
    public NewOrder {
      java.util.Objects.requireNonNull(terms, "terms");
    }

    @Override
    public String messageType() {
      return quickfix.fix44.NewOrderSingle.MSGTYPE;
    }

    @Override
    public Type commandType() {
      return Type.COMMAND_TYPE_NEW;
    }
  }

  /** A cancellation with the durable order context required by v2 admission. */
  record Cancel(String symbol, Side side) implements WalCommand {
    /** Creates an empty cancellation context for codec validation paths. */
    public Cancel() {
      this("", Side.SIDE_UNSPECIFIED);
    }

    /** Normalizes nullable context while preserving explicit values. */
    public Cancel {
      symbol = symbol == null ? "" : symbol;
      side = side == null ? Side.SIDE_UNSPECIFIED : side;
    }

    /** Returns whether this cancellation carries complete v2 admission context. */
    public boolean hasOrderContext() {
      return !symbol.isBlank() && side != Side.SIDE_UNSPECIFIED;
    }

    @Override
    public String messageType() {
      return quickfix.fix44.OrderCancelRequest.MSGTYPE;
    }

    @Override
    public Type commandType() {
      return Type.COMMAND_TYPE_CANCEL;
    }
  }
}
