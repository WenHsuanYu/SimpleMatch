package com.simplematch.riskservice.submission;

import java.util.Objects;

/**
 * Represents the normalized payload fields for a submission.
 */
public final class SubmissionCommand {
  private final RequestMetadata requestMetadata;
  private final OrderDetails orderDetails;

  /**
   * Represents a normalized order identifier.
   */
  public record CommandId(String value) {
    public CommandId {
      value = nullToEmpty(value);
    }

    public static CommandId empty() {
      return new CommandId("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /**
   * Represents a normalized order identifier.
   */
  public record OrderId(String value) {
    public OrderId {
      value = nullToEmpty(value);
    }

    public static OrderId empty() {
      return new OrderId("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /**
   * Represents a normalized account identifier.
   */
  public record AccountId(String value) {
    public AccountId {
      value = nullToEmpty(value);
    }

    public static AccountId empty() {
      return new AccountId("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /**
   * Represents a normalized session identifier.
   */
  public record SessionId(String value) {
    public SessionId {
      value = nullToEmpty(value);
    }

    public static SessionId empty() {
      return new SessionId("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /**
   * Represents a normalized client order identifier.
   */
  public record ClientOrderId(String value) {
    public ClientOrderId {
      value = nullToEmpty(value);
    }

    public static ClientOrderId empty() {
      return new ClientOrderId("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /**
   * Represents a normalized quantity.
   */
  public record Quantity(String value) {
    public Quantity {
      value = nullToEmpty(value);
    }

    public static Quantity empty() {
      return new Quantity("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /**
   * Represents a normalized price.
   */
  public record Price(String value) {
    public Price {
      value = nullToEmpty(value);
    }

    public static Price empty() {
      return new Price("");
    }

    public boolean isBlank() {
      return value.isBlank();
    }
  }

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
  public static final class RequestMetadata {
    private final CommandId commandId;
    private final OrderId orderId;
    private final AccountId accountId;
    private final SessionId sessionId;
    private final ClientOrderId clientOrderId;
    private final ClientOrderId originalClientOrderId;

    public RequestMetadata(
        String commandId,
        String orderId,
        String accountId,
        String sessionId,
        String clientOrderId,
        String originalClientOrderId) {
      this(
          new CommandId(commandId),
          new OrderId(orderId),
          new AccountId(accountId),
              new SessionId(sessionId),
          new ClientOrderId(clientOrderId),
          new ClientOrderId(originalClientOrderId));
    }

    private RequestMetadata(
        CommandId commandId,
        OrderId orderId,
        AccountId accountId,
        SessionId sessionId,
        ClientOrderId clientOrderId,
        ClientOrderId originalClientOrderId) {
      this.commandId = commandId == null ? CommandId.empty() : commandId;
      this.orderId = orderId == null ? OrderId.empty() : orderId;
      this.accountId = accountId == null ? AccountId.empty() : accountId;
      this.sessionId = sessionId == null ? SessionId.empty() : sessionId;
      this.clientOrderId = clientOrderId == null ? ClientOrderId.empty() : clientOrderId;
      this.originalClientOrderId = originalClientOrderId == null ? ClientOrderId.empty() : originalClientOrderId;
    }

    /**
     * Returns an empty request metadata object.
     *
     * @return empty request metadata
     */
    public static RequestMetadata empty() {
      return new RequestMetadata("", "", "", "", "", "");
    }

    public String commandId() {
      return commandId.value();
    }

    public CommandId commandIdValue() {
      return commandId;
    }

    public String orderId() {
      return orderId.value();
    }

    public OrderId orderIdValue() {
      return orderId;
    }

    public String accountId() {
      return accountId.value();
    }

    public AccountId accountIdValue() {
      return accountId;
    }

    public String sessionId() {
      return sessionId.value();
    }

    public SessionId sessionIdValue() {
      return sessionId;
    }

    public String clientOrderId() {
      return clientOrderId.value();
    }

    public ClientOrderId clientOrderIdValue() {
      return clientOrderId;
    }

    public String originalClientOrderId() {
      return originalClientOrderId.value();
    }

    public ClientOrderId originalClientOrderIdValue() {
      return originalClientOrderId;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof RequestMetadata that)) {
        return false;
      }
      return commandId.equals(that.commandId)
          && orderId.equals(that.orderId)
          && accountId.equals(that.accountId)
          && sessionId.equals(that.sessionId)
          && clientOrderId.equals(that.clientOrderId)
          && originalClientOrderId.equals(that.originalClientOrderId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          commandId,
          orderId,
          accountId,
          sessionId,
          clientOrderId,
          originalClientOrderId);
    }

    @Override
    public String toString() {
      return "RequestMetadata[commandId=" + commandId
          + ", orderId=" + orderId
          + ", accountId=" + accountId
          + ", sessionId=" + sessionId
          + ", clientOrderId=" + clientOrderId
          + ", originalClientOrderId=" + originalClientOrderId + "]";
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
  public static final class OrderDetails {
    private final String symbol;
    private final Side side;
    private final Quantity quantity;
    private final Price price;
    private final OrderType orderType;
    private final TimeInForce tif;

    public OrderDetails(
        String symbol,
        Side side,
        String quantity,
        String price,
        OrderType orderType,
        TimeInForce tif) {
      this(symbol, side, new Quantity(quantity), new Price(price), orderType, tif);
    }

    private OrderDetails(
        String symbol,
        Side side,
        Quantity quantity,
        Price price,
        OrderType orderType,
        TimeInForce tif) {
      this.symbol = nullToEmpty(symbol);
      this.side = side == null ? Side.SIDE_UNSPECIFIED : side;
      this.quantity = quantity == null ? Quantity.empty() : quantity;
      this.price = price == null ? Price.empty() : price;
      this.orderType = orderType == null ? OrderType.ORDER_TYPE_UNSPECIFIED : orderType;
      this.tif = tif == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : tif;
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

    public String symbol() {
      return symbol;
    }

    public Side side() {
      return side;
    }

    public String quantity() {
      return quantity.value();
    }

    public Quantity quantityValue() {
      return quantity;
    }

    public String price() {
      return price.value();
    }

    public Price priceValue() {
      return price;
    }

    public OrderType orderType() {
      return orderType;
    }

    public TimeInForce tif() {
      return tif;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof OrderDetails that)) {
        return false;
      }
      return symbol.equals(that.symbol)
          && side == that.side
          && quantity.equals(that.quantity)
          && price.equals(that.price)
          && orderType == that.orderType
          && tif == that.tif;
    }

    @Override
    public int hashCode() {
      return Objects.hash(symbol, side, quantity, price, orderType, tif);
    }

    @Override
    public String toString() {
      return "OrderDetails[symbol=" + symbol
          + ", side=" + side
          + ", quantity=" + quantity
          + ", price=" + price
          + ", orderType=" + orderType
          + ", tif=" + tif + "]";
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

  public CommandId commandIdValue() {
    return requestMetadata.commandIdValue();
  }

  public String orderId() {
    return requestMetadata.orderId();
  }

  public OrderId orderIdValue() {
    return requestMetadata.orderIdValue();
  }

  public String accountId() {
    return requestMetadata.accountId();
  }

  public AccountId accountIdValue() {
    return requestMetadata.accountIdValue();
  }

  public String sessionId() {
    return requestMetadata.sessionId();
  }

  public SessionId sessionIdValue() {
    return requestMetadata.sessionIdValue();
  }

  public String clientOrderId() {
    return requestMetadata.clientOrderId();
  }

  public ClientOrderId clientOrderIdValue() {
    return requestMetadata.clientOrderIdValue();
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

  public Quantity quantityValue() {
    return orderDetails.quantityValue();
  }

  public String price() {
    return orderDetails.price();
  }

  public Price priceValue() {
    return orderDetails.priceValue();
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

  public ClientOrderId originalClientOrderIdValue() {
    return requestMetadata.originalClientOrderIdValue();
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
    return commandIdValue().isBlank()
      && orderIdValue().isBlank()
        && accountIdValue().isBlank()
        && sessionIdValue().isBlank()
      && clientOrderIdValue().isBlank()
        && symbol().isBlank()
        && side() == Side.SIDE_UNSPECIFIED
      && quantityValue().isBlank()
      && priceValue().isBlank()
        && orderType() == OrderType.ORDER_TYPE_UNSPECIFIED
        && tif() == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
      && originalClientOrderIdValue().isBlank();
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