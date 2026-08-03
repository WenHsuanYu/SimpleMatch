package com.simplematch.quickfixgateway.wal;

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

  /** A cancellation whose order terms are not part of the command model. */
  record Cancel() implements WalCommand {
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
