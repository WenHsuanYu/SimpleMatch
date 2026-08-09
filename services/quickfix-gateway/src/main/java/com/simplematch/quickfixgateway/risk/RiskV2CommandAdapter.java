package com.simplematch.quickfixgateway.risk;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import com.simplematch.contracts.v2.V1OrderCommandAdapter;
import com.simplematch.contracts.v2.VenueMic;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Adapts FIX-facing v1 command identities to the opaque order identity required by v2 Risk. */
public final class RiskV2CommandAdapter {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final V1OrderCommandAdapter delegate;

  /** Creates the adapter for the configured ingress venue. */
  public RiskV2CommandAdapter(VenueMic ingressVenue) {
    delegate = new V1OrderCommandAdapter(Objects.requireNonNull(ingressVenue, "ingressVenue"));
  }

  /** Converts a FIX-facing new command to the production v2 Risk contract. */
  public NewOrderCommand toNewOrder(OrderCommand command) {
    return delegate.toNewOrder(withRiskOrderId(command));
  }

  /** Converts a FIX-facing cancel command to the production v2 Risk contract. */
  public CancelOrderCommand toCancelOrder(OrderCommand command) {
    return delegate.toCancelOrder(withRiskOrderId(command));
  }

  /** Returns the stable opaque v2 Risk order identity for one compatibility command. */
  public String riskOrderId(OrderCommand command) {
    Objects.requireNonNull(command, "command");
    final String clientOrderId =
        command.getCommandType() == CommandType.COMMAND_TYPE_CANCEL
            ? command.getOrigClOrdId()
            : command.getClOrdId();
    final String tradingDay =
        Instant.ofEpochMilli(command.getMetadata().getCreatedAtUnixMs())
            .atZone(TAIPEI)
            .toLocalDate()
            .toString();
    final String identityKey =
        command.getSenderCompId()
            + "\u0000"
            + command.getTargetCompId()
            + "\u0000"
            + tradingDay
            + "\u0000"
            + clientOrderId;
    return UUID.nameUUIDFromBytes(identityKey.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private OrderCommand withRiskOrderId(OrderCommand command) {
    return command.toBuilder().setOrderId(riskOrderId(command)).build();
  }
}
