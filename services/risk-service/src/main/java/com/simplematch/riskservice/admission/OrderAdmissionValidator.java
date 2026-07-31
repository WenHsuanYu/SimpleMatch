package com.simplematch.riskservice.admission;

import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;
import org.springframework.stereotype.Component;

/** Validates v2 order fields without transport, database, or account side effects. */
@Component
public final class OrderAdmissionValidator {
  /** Converts a valid v2 command into the journal's transport-independent carrier. */
  public AdmissionCommand validate(NewOrderCommand command) {
    if (command == null) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand("new order command is required"));
    }
    return NewOrderAdmissionCommandAssembler.assemble(command);
  }

  /** Converts a valid cancel command into the same durable identity carrier. */
  public AdmissionCommand validateCancel(CancelOrderCommand command) {
    if (command == null) {
      throw new AdmissionValidationException(
          AdmissionFailure.invalidCommand("cancel command is required"));
    }
    return CancelOrderAdmissionCommandAssembler.assemble(command);
  }
}
