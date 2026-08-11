package com.simplematch.quickfixgateway.operations;

/** Transport-neutral operator command accepted by the single Gateway admission authority. */
public record GatewayOperationalCommand(GatewayOperation operation, String actor, String reason) {
  /** Validates one CLI, HTTP, or job adapter command before it reaches the domain controller. */
  public GatewayOperationalCommand {
    operation = OperationalStatusValidation.required(operation, "operation");
    actor = OperationalStatusValidation.requiredText(actor, "actor");
    reason = OperationalStatusValidation.requiredText(reason, "reason");
  }
}
