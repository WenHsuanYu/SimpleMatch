package com.simplematch.riskservice.submission;

import java.util.Objects;

/**
 * Represents the normalized payload fields for a submission.
 */
public final class SubmissionCommand {
  private final RequestMetadata requestMetadata;
  private final OrderDetails orderDetails;

  /**
   * Groups the request-scoped identifiers and session context for a submission payload.
   *
   * @param commandId the transport-level command identifier
   * @param orderId the internal or client-visible order identifier
   * @param accountId the account that owns the submission
   * @param sessionId the session that produced the submission
   * @param clientOrderId the client-provided order identifier
   * @param originalClientOrderId the original client order identifier for replacement or cancel flows
   */
  public record RequestMetadata(
      String commandId,
      String orderId,
      String accountId,
      String sessionId,
      String clientOrderId,
      String originalClientOrderId) {
    public RequestMetadata {
      commandId = nullToEmpty(commandId);
      orderId = nullToEmpty(orderId);
      accountId = nullToEmpty(accountId);
      sessionId = nullToEmpty(sessionId);
      clientOrderId = nullToEmpty(clientOrderId);
      originalClientOrderId = nullToEmpty(originalClientOrderId);
    }

    /**
     * Returns an empty request metadata object.
     *
     * @return empty request metadata
     */
    public static RequestMetadata empty() {
      return new RequestMetadata("", "", "", "", "", "");
    }
  }

  /**
   * Groups the order-specific payload fields for a submission payload.
   *
   * @param symbol the submitted symbol
   * @param side the submitted side
   * @param quantity the submitted quantity
   * @param price the submitted price
   * @param orderType the submitted order type
   * @param tif the submitted time in force
   */
  public record OrderDetails(
      String symbol,
      Side side,
      String quantity,
      String price,
      OrderType orderType,
      TimeInForce tif) {
    public OrderDetails {
      symbol = nullToEmpty(symbol);
      side = side == null ? Side.SIDE_UNSPECIFIED : side;
      quantity = nullToEmpty(quantity);
      price = nullToEmpty(price);
      orderType = orderType == null ? OrderType.ORDER_TYPE_UNSPECIFIED : orderType;
      tif = tif == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : tif;
    }

    /**
     * Returns empty order details.
     *
     * @return empty order details
     */
    public static OrderDetails empty() {
      return new OrderDetails(
          "",
          Side.SIDE_UNSPECIFIED,
          "",
          "",
          OrderType.ORDER_TYPE_UNSPECIFIED,
          TimeInForce.TIME_IN_FORCE_UNSPECIFIED);
    }
  }

  /**
   * Creates a submission payload from grouped request metadata and order details.
   *
   * @param requestMetadata the request-scoped identifiers and context
   * @param orderDetails the order-specific payload fields
   */
  public SubmissionCommand(RequestMetadata requestMetadata, OrderDetails orderDetails) {
    this.requestMetadata = requestMetadata == null ? RequestMetadata.empty() : requestMetadata;
    this.orderDetails = orderDetails == null ? OrderDetails.empty() : orderDetails;
  }

  /**
   * Creates a submission payload from grouped request metadata and order details.
   *
   * @param requestMetadata the request-scoped identifiers and context
   * @param orderDetails the order-specific payload fields
   * @return the normalized submission payload
   */
  public static SubmissionCommand create(
      RequestMetadata requestMetadata,
      OrderDetails orderDetails) {
    return new SubmissionCommand(requestMetadata, orderDetails);
  }

  /**
   * Returns the grouped request-scoped identifiers and session context.
   *
   * @return the normalized request metadata
   */
  public RequestMetadata requestMetadata() {
    return requestMetadata;
  }

  /**
   * Returns the grouped order-specific payload fields.
   *
   * @return the normalized order details
   */
  public OrderDetails orderDetails() {
    return orderDetails;
  }

  public String commandId() {
    return requestMetadata.commandId();
  }

  public String orderId() {
    return requestMetadata.orderId();
  }

  public String accountId() {
    return requestMetadata.accountId();
  }

  public String sessionId() {
    return requestMetadata.sessionId();
  }

  public String clientOrderId() {
    return requestMetadata.clientOrderId();
  }

  public String symbol() {
    return orderDetails.symbol();
  }

  public Side side() {
    return orderDetails.side();
  }

  public String quantity() {
    return orderDetails.quantity();
  }

  public String price() {
    return orderDetails.price();
  }

  public OrderType orderType() {
    return orderDetails.orderType();
  }

  public TimeInForce tif() {
    return orderDetails.tif();
  }

  public String originalClientOrderId() {
    return requestMetadata.originalClientOrderId();
  }

  /**
   * Returns a command with no payload fields.
   *
   * @return an empty submission payload
   */
  public static SubmissionCommand unspecified() {
    return create(RequestMetadata.empty(), OrderDetails.empty());
  }

  /**
   * Returns whether every payload field is blank or unspecified.
   *
   * @return {@code true} when the command carries no payload fields
   */
  public boolean hasNoPayloadFields() {
    return commandId().isBlank()
        && orderId().isBlank()
        && accountId().isBlank()
        && sessionId().isBlank()
        && clientOrderId().isBlank()
        && symbol().isBlank()
        && side() == Side.SIDE_UNSPECIFIED
        && quantity().isBlank()
        && price().isBlank()
        && orderType() == OrderType.ORDER_TYPE_UNSPECIFIED
        && tif() == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
        && originalClientOrderId().isBlank();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SubmissionCommand that)) {
      return false;
    }
    return requestMetadata.equals(that.requestMetadata)
        && orderDetails.equals(that.orderDetails);
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestMetadata, orderDetails);
  }

  @Override
  public String toString() {
    return "SubmissionCommand[requestMetadata=" + requestMetadata
        + ", orderDetails=" + orderDetails + "]";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}