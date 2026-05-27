package com.simplematch.riskservice.submission;

public record SubmissionCommand(
    String commandId,
    String orderId,
    String accountId,
    String sessionId,
    String clientOrderId,
    String symbol,
    Side side,
    String quantity,
    String price,
    OrderType orderType,
    TimeInForce tif,
    CommandType commandType,
    String originalClientOrderId) {
  public SubmissionCommand {
    commandId = normalize(commandId);
    orderId = normalize(orderId);
    accountId = normalize(accountId);
    sessionId = normalize(sessionId);
    clientOrderId = normalize(clientOrderId);
    symbol = normalize(symbol);
    side = side == null ? Side.SIDE_UNSPECIFIED : side;
    quantity = normalize(quantity);
    price = normalize(price);
    orderType = orderType == null ? OrderType.ORDER_TYPE_UNSPECIFIED : orderType;
    tif = tif == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : tif;
    commandType = commandType == null ? CommandType.COMMAND_TYPE_UNSPECIFIED : commandType;
    originalClientOrderId = normalize(originalClientOrderId);
  }

  public static SubmissionCommand empty() {
    return new SubmissionCommand(
        "",
        "",
        "",
        "",
        "",
        "",
        Side.SIDE_UNSPECIFIED,
        "",
        "",
        OrderType.ORDER_TYPE_UNSPECIFIED,
        TimeInForce.TIME_IN_FORCE_UNSPECIFIED,
        CommandType.COMMAND_TYPE_UNSPECIFIED,
        "");
  }

  public SubmissionCommand withCommandType(CommandType expectedType) {
    final CommandType resolvedType = expectedType == null
        ? CommandType.COMMAND_TYPE_UNSPECIFIED
        : expectedType;
    if (resolvedType == commandType) {
      return this;
    }
    return new SubmissionCommand(
        commandId,
        orderId,
        accountId,
        sessionId,
        clientOrderId,
        symbol,
        side,
        quantity,
        price,
        orderType,
        tif,
        resolvedType,
        originalClientOrderId);
  }

  public boolean isEmpty() {
    return commandId.isBlank()
        && orderId.isBlank()
        && accountId.isBlank()
        && sessionId.isBlank()
        && clientOrderId.isBlank()
        && symbol.isBlank()
        && side == Side.SIDE_UNSPECIFIED
        && quantity.isBlank()
        && price.isBlank()
        && orderType == OrderType.ORDER_TYPE_UNSPECIFIED
        && tif == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
        && originalClientOrderId.isBlank();
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}