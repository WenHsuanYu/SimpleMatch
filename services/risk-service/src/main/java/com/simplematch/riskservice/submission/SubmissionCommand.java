package com.simplematch.riskservice.submission;

import java.time.LocalDate;
import java.util.Objects;

/** Represents the normalized payload fields for a submission. */
@SuppressWarnings(
    "PMD.TooManyMethods") // Normalized submission facade preserves existing transport callers.
public final class SubmissionCommand {
  private final RequestMetadata requestMetadata;
  private final OrderDetails orderDetails;

  /** Represents a normalized order identifier. */
  public record CommandId(String value) {
    /** Normalizes the command identifier value. */
    public CommandId {
      value = nullToEmpty(value);
    }

    /** Returns an empty command identifier. */
    public static CommandId empty() {
      return new CommandId("");
    }

    /** Returns whether the command identifier is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized order identifier. */
  public record OrderId(String value) {
    /** Normalizes the order identifier value. */
    public OrderId {
      value = nullToEmpty(value);
    }

    /** Returns an empty order identifier. */
    public static OrderId empty() {
      return new OrderId("");
    }

    /** Returns whether the order identifier is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized account identifier. */
  public record AccountId(String value) {
    /** Normalizes the account identifier value. */
    public AccountId {
      value = nullToEmpty(value);
    }

    /** Returns an empty account identifier. */
    public static AccountId empty() {
      return new AccountId("");
    }

    /** Returns whether the account identifier is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized FIX SenderCompID. */
  public record SenderCompId(String value) {
    /** Normalizes the FIX SenderCompID value. */
    public SenderCompId {
      value = nullToEmpty(value);
    }

    /** Returns an empty FIX SenderCompID. */
    public static SenderCompId empty() {
      return new SenderCompId("");
    }

    /** Returns whether the FIX SenderCompID is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized FIX TargetCompID. */
  public record TargetCompId(String value) {
    /** Normalizes the FIX TargetCompID value. */
    public TargetCompId {
      value = nullToEmpty(value);
    }

    /** Returns an empty FIX TargetCompID. */
    public static TargetCompId empty() {
      return new TargetCompId("");
    }

    /** Returns whether the FIX TargetCompID is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized FIX ClOrdID. */
  public record ClOrdId(String value) {
    /** Normalizes the FIX ClOrdID value. */
    public ClOrdId {
      value = nullToEmpty(value);
    }

    /** Returns an empty FIX ClOrdID. */
    public static ClOrdId empty() {
      return new ClOrdId("");
    }

    /** Returns whether the FIX ClOrdID is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized FIX OrigClOrdID. */
  public record OrigClOrdId(String value) {
    /** Normalizes the FIX OrigClOrdID value. */
    public OrigClOrdId {
      value = nullToEmpty(value);
    }

    /** Returns an empty FIX OrigClOrdID. */
    public static OrigClOrdId empty() {
      return new OrigClOrdId("");
    }

    /** Returns whether the FIX OrigClOrdID is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized quantity. */
  public record Quantity(String value) {
    /** Normalizes the quantity value. */
    public Quantity {
      value = nullToEmpty(value);
    }

    /** Returns an empty quantity. */
    public static Quantity empty() {
      return new Quantity("");
    }

    /** Returns whether the quantity is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Represents a normalized price. */
  public record Price(String value) {
    /** Normalizes the price value. */
    public Price {
      value = nullToEmpty(value);
    }

    /** Returns an empty price. */
    public static Price empty() {
      return new Price("");
    }

    /** Returns whether the price is blank. */
    public boolean isBlank() {
      return value.isBlank();
    }
  }

  /** Groups the request-scoped identifiers and FIX routing context for a submission payload. */
  @SuppressWarnings(
      "PMD.TooManyMethods") // Request metadata exposes typed and wire-compatible identifiers.
  public static final class RequestMetadata {
    private final CommandId commandId;
    private final OrderId orderId;
    private final AccountId accountId;
    private final SenderCompId senderCompId;
    private final TargetCompId targetCompId;
    private final ClOrdId clOrdId;
    private final OrigClOrdId origClOrdId;
    private final LocalDate tradingDay;

    /**
     * Creates request metadata from transport and business identifiers.
     *
     * @param commandId the transport-level command identifier
     * @param orderId the internal or client-visible order identifier
     * @param accountId the account that owns the submission
     * @param senderCompId the FIX SenderCompID carried by the submission payload
     * @param targetCompId the FIX TargetCompID carried by the submission payload
     * @param clOrdId the FIX ClOrdID carried by the submission payload
     * @param origClOrdId the FIX OrigClOrdID for cancel flows
     */
    public RequestMetadata(
        String commandId,
        String orderId,
        String accountId,
        String senderCompId,
        String targetCompId,
        String clOrdId,
        String origClOrdId) {
      this(commandId, orderId, accountId, senderCompId, targetCompId, clOrdId, origClOrdId, null);
    }

    /**
     * Creates request metadata from transport and business identifiers.
     *
     * @param commandId the transport-level command identifier
     * @param orderId the internal or client-visible order identifier
     * @param accountId the account that owns the submission
     * @param senderCompId the FIX SenderCompID carried by the submission payload
     * @param targetCompId the FIX TargetCompID carried by the submission payload
     * @param clOrdId the FIX ClOrdID carried by the submission payload
     * @param origClOrdId the FIX OrigClOrdID for cancel flows
     * @param tradingDay the business trading day derived from the gateway event timestamp when
     *     available
     */
    public RequestMetadata(
        String commandId,
        String orderId,
        String accountId,
        String senderCompId,
        String targetCompId,
        String clOrdId,
        String origClOrdId,
        LocalDate tradingDay) {
      this(
          new CommandId(commandId),
          new OrderId(orderId),
          new AccountId(accountId),
          new SenderCompId(senderCompId),
          new TargetCompId(targetCompId),
          new ClOrdId(clOrdId),
          new OrigClOrdId(origClOrdId),
          tradingDay);
    }

    private RequestMetadata(
        CommandId commandId,
        OrderId orderId,
        AccountId accountId,
        SenderCompId senderCompId,
        TargetCompId targetCompId,
        ClOrdId clOrdId,
        OrigClOrdId origClOrdId,
        LocalDate tradingDay) {
      this.commandId = commandId == null ? CommandId.empty() : commandId;
      this.orderId = orderId == null ? OrderId.empty() : orderId;
      this.accountId = accountId == null ? AccountId.empty() : accountId;
      this.senderCompId = senderCompId == null ? SenderCompId.empty() : senderCompId;
      this.targetCompId = targetCompId == null ? TargetCompId.empty() : targetCompId;
      this.clOrdId = clOrdId == null ? ClOrdId.empty() : clOrdId;
      this.origClOrdId = origClOrdId == null ? OrigClOrdId.empty() : origClOrdId;
      this.tradingDay = tradingDay;
    }

    /**
     * Returns an empty request metadata object.
     *
     * @return empty request metadata
     */
    public static RequestMetadata empty() {
      return new RequestMetadata("", "", "", "", "", "", "", null);
    }

    /** Returns the command identifier text. */
    public String commandId() {
      return commandId.value();
    }

    /** Returns the typed command identifier. */
    public CommandId commandIdValue() {
      return commandId;
    }

    /** Returns the order identifier text. */
    public String orderId() {
      return orderId.value();
    }

    /** Returns the typed order identifier. */
    public OrderId orderIdValue() {
      return orderId;
    }

    /** Returns the account identifier text. */
    public String accountId() {
      return accountId.value();
    }

    /** Returns the typed account identifier. */
    public AccountId accountIdValue() {
      return accountId;
    }

    /** Returns the FIX SenderCompID text. */
    public String senderCompId() {
      return senderCompId.value();
    }

    /** Returns the typed FIX SenderCompID. */
    public SenderCompId senderCompIdValue() {
      return senderCompId;
    }

    /** Returns the FIX TargetCompID text. */
    public String targetCompId() {
      return targetCompId.value();
    }

    /** Returns the typed FIX TargetCompID. */
    public TargetCompId targetCompIdValue() {
      return targetCompId;
    }

    /** Returns the FIX ClOrdID text. */
    public String clOrdId() {
      return clOrdId.value();
    }

    /** Returns the typed FIX ClOrdID. */
    public ClOrdId clOrdIdValue() {
      return clOrdId;
    }

    /** Returns the FIX OrigClOrdID text. */
    public String origClOrdId() {
      return origClOrdId.value();
    }

    /** Returns the typed FIX OrigClOrdID. */
    public OrigClOrdId origClOrdIdValue() {
      return origClOrdId;
    }

    /** Returns the optional business trading day. */
    public LocalDate tradingDay() {
      return tradingDay;
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
          && senderCompId.equals(that.senderCompId)
          && targetCompId.equals(that.targetCompId)
          && clOrdId.equals(that.clOrdId)
          && origClOrdId.equals(that.origClOrdId)
          && Objects.equals(tradingDay, that.tradingDay);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          commandId,
          orderId,
          accountId,
          senderCompId,
          targetCompId,
          clOrdId,
          origClOrdId,
          tradingDay);
    }

    @Override
    public String toString() {
      return "RequestMetadata[commandId="
          + commandId
          + ", orderId="
          + orderId
          + ", accountId="
          + accountId
          + ", senderCompId="
          + senderCompId
          + ", targetCompId="
          + targetCompId
          + ", clOrdId="
          + clOrdId
          + ", origClOrdId="
          + origClOrdId
          + ", tradingDay="
          + tradingDay
          + "]";
    }
  }

  /** Groups the order-specific payload fields for a submission payload. */
  @SuppressWarnings(
      "PMD.TooManyMethods") // Order details exposes typed and wire-compatible quantities.
  public static final class OrderDetails {
    private final String symbol;
    private final Side side;
    private final Quantity quantity;
    private final Price price;
    private final OrderType orderType;
    private final TimeInForce tif;

    /**
     * Creates order details from the incoming payload fields.
     *
     * @param symbol the submitted symbol
     * @param side the submitted side
     * @param quantity the submitted quantity
     * @param price the submitted price
     * @param orderType the submitted order type
     * @param tif the submitted time in force
     */
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

    /** Returns the submitted symbol. */
    public String symbol() {
      return symbol;
    }

    /** Returns the submitted side. */
    public Side side() {
      return side;
    }

    /** Returns the quantity text. */
    public String quantity() {
      return quantity.value();
    }

    /** Returns the typed quantity. */
    public Quantity quantityValue() {
      return quantity;
    }

    /** Returns the price text. */
    public String price() {
      return price.value();
    }

    /** Returns the typed price. */
    public Price priceValue() {
      return price;
    }

    /** Returns the submitted order type. */
    public OrderType orderType() {
      return orderType;
    }

    /** Returns the submitted time in force. */
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
      return "OrderDetails[symbol="
          + symbol
          + ", side="
          + side
          + ", quantity="
          + quantity
          + ", price="
          + price
          + ", orderType="
          + orderType
          + ", tif="
          + tif
          + "]";
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
      RequestMetadata requestMetadata, OrderDetails orderDetails) {
    return new SubmissionCommand(requestMetadata, orderDetails);
  }

  /**
   * Returns the grouped request-scoped identifiers and FIX routing context.
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

  /** Returns the command identifier text. */
  public String commandId() {
    return requestMetadata.commandId();
  }

  /** Returns the typed command identifier. */
  public CommandId commandIdValue() {
    return requestMetadata.commandIdValue();
  }

  /** Returns the order identifier text. */
  public String orderId() {
    return requestMetadata.orderId();
  }

  /** Returns the typed order identifier. */
  public OrderId orderIdValue() {
    return requestMetadata.orderIdValue();
  }

  /** Returns the account identifier text. */
  public String accountId() {
    return requestMetadata.accountId();
  }

  /** Returns the typed account identifier. */
  public AccountId accountIdValue() {
    return requestMetadata.accountIdValue();
  }

  /** Returns the FIX SenderCompID text. */
  public String senderCompId() {
    return requestMetadata.senderCompId();
  }

  /** Returns the typed FIX SenderCompID. */
  public SenderCompId senderCompIdValue() {
    return requestMetadata.senderCompIdValue();
  }

  /** Returns the FIX TargetCompID text. */
  public String targetCompId() {
    return requestMetadata.targetCompId();
  }

  /** Returns the typed FIX TargetCompID. */
  public TargetCompId targetCompIdValue() {
    return requestMetadata.targetCompIdValue();
  }

  /** Returns the FIX ClOrdID text. */
  public String clOrdId() {
    return requestMetadata.clOrdId();
  }

  /** Returns the typed FIX ClOrdID. */
  public ClOrdId clOrdIdValue() {
    return requestMetadata.clOrdIdValue();
  }

  /** Returns the submitted symbol. */
  public String symbol() {
    return orderDetails.symbol();
  }

  /** Returns the submitted side. */
  public Side side() {
    return orderDetails.side();
  }

  /** Returns the quantity text. */
  public String quantity() {
    return orderDetails.quantity();
  }

  /** Returns the typed quantity. */
  public Quantity quantityValue() {
    return orderDetails.quantityValue();
  }

  /** Returns the price text. */
  public String price() {
    return orderDetails.price();
  }

  /** Returns the typed price. */
  public Price priceValue() {
    return orderDetails.priceValue();
  }

  /** Returns the submitted order type. */
  public OrderType orderType() {
    return orderDetails.orderType();
  }

  /** Returns the submitted time in force. */
  public TimeInForce tif() {
    return orderDetails.tif();
  }

  /** Returns the FIX OrigClOrdID text. */
  public String origClOrdId() {
    return requestMetadata.origClOrdId();
  }

  /** Returns the typed FIX OrigClOrdID. */
  public OrigClOrdId origClOrdIdValue() {
    return requestMetadata.origClOrdIdValue();
  }

  /** Returns the optional business trading day. */
  public LocalDate tradingDay() {
    return requestMetadata.tradingDay();
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
        && senderCompIdValue().isBlank()
        && targetCompIdValue().isBlank()
        && clOrdIdValue().isBlank()
        && symbol().isBlank()
        && side() == Side.SIDE_UNSPECIFIED
        && quantityValue().isBlank()
        && priceValue().isBlank()
        && orderType() == OrderType.ORDER_TYPE_UNSPECIFIED
        && tif() == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
        && origClOrdIdValue().isBlank()
        && tradingDay() == null;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SubmissionCommand that)) {
      return false;
    }
    return requestMetadata.equals(that.requestMetadata) && orderDetails.equals(that.orderDetails);
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestMetadata, orderDetails);
  }

  @Override
  public String toString() {
    return "SubmissionCommand[requestMetadata="
        + requestMetadata
        + ", orderDetails="
        + orderDetails
        + "]";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
