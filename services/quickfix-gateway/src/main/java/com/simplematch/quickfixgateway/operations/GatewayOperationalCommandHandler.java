package com.simplematch.quickfixgateway.operations;

import java.util.Objects;

/**
 * Transport-neutral command boundary for the five accepted Gateway operational commands.
 *
 * <p>A CLI, internal HTTP adapter, or scheduled job may call this boundary, but none may mutate the
 * admission gate directly or invent another operation name.
 */
public final class GatewayOperationalCommandHandler {
  private final GatewayOperationalController controller;

  /** Creates the command boundary over the Gateway's sole operational controller. */
  public GatewayOperationalCommandHandler(GatewayOperationalController controller) {
    this.controller = Objects.requireNonNull(controller, "controller");
  }

  /** Executes one of the five accepted Gateway operational commands. */
  public GatewayOperationResult execute(GatewayOperationalCommand command) {
    final GatewayOperationalCommand requiredCommand = Objects.requireNonNull(command, "command");
    return switch (requiredCommand.operation()) {
      case STATUS -> controller.status();
      case OPEN -> controller.open(requiredCommand.actor(), requiredCommand.reason());
      case PAUSE_NEW_ORDERS ->
          controller.pauseNewOrders(requiredCommand.actor(), requiredCommand.reason());
      case INTERRUPT_MARKET ->
          controller.interruptMarket(requiredCommand.actor(), requiredCommand.reason());
      case CLOSE_DAY -> controller.closeDay(requiredCommand.actor(), requiredCommand.reason());
    };
  }
}
