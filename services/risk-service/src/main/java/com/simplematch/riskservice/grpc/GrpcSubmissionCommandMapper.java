package com.simplematch.riskservice.grpc;

import com.simplematch.contracts.orders.v1.CommandType;
import com.simplematch.contracts.orders.v1.OrderCommand;
import com.simplematch.riskservice.submission.SubmissionCommand;

final class GrpcSubmissionCommandMapper {
  SubmissionCommand map(OrderCommand command, CommandType expectedType) {
    final SubmissionCommand emptyCommand = SubmissionCommand.empty().withCommandType(expectedType);
    if (command == null || OrderCommand.getDefaultInstance().equals(command)) {
      return emptyCommand;
    }

    final SubmissionCommand mappedCommand = new SubmissionCommand(
        command.getCommandId(),
        command.getOrderId(),
        command.getAccountId(),
        command.getSessionId(),
        command.getClientOrderId(),
        command.getSymbol(),
        command.getSide(),
        command.getQuantity(),
        command.getPrice(),
        command.getOrderType(),
        command.getTif(),
        command.getCommandType(),
        command.getOriginalClientOrderId());
    return mappedCommand.withCommandType(expectedType);
  }
}