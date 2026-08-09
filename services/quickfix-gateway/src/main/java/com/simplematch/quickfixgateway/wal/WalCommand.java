package com.simplematch.quickfixgateway.wal;

import com.simplematch.contracts.common.v1.Side;
import com.simplematch.contracts.orders.v1.CommandType;

/** Command-specific facts carried by one durable inbound FIX command. */
public sealed interface WalCommand permits WalCommand.NewOrder, WalCommand.Cancel {
  /** FIX message type represented by this command. */
  String messageType();

  /** Gateway command classification represented by this command. */
  CommandType commandType();

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
    public CommandType commandType() {
      return CommandType.COMMAND_TYPE_NEW;
    }
  }

  /** A cancellation with the durable order context required by v2 admission. */
  record Cancel(String symbol, Side side) implements WalCommand {
    /** Creates the legacy v1 placeholder form used when reading old WAL records. */
    public Cancel() {
      this("", Side.SIDE_UNSPECIFIED);
    }

    /** Normalizes nullable legacy context while preserving explicit v2 values. */
    public Cancel {
      symbol = symbol == null ? "" : symbol;
      side = side == null ? Side.SIDE_UNSPECIFIED : side;
    }

    /** Returns whether this cancellation carries v2 admission context. */
    public boolean hasOrderContext() {
      return !symbol.isBlank() && side != Side.SIDE_UNSPECIFIED;
    }

    @Override
    public String messageType() {
      return quickfix.fix44.OrderCancelRequest.MSGTYPE;
    }

    @Override
    public CommandType commandType() {
      return CommandType.COMMAND_TYPE_CANCEL;
    }
  }
}
