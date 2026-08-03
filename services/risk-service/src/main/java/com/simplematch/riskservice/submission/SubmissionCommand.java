package com.simplematch.riskservice.submission;

import java.time.LocalDate;

/** Represents the normalized payload fields for a submission. */
public record SubmissionCommand(RequestMetadata requestMetadata, OrderDetails orderDetails) {

  /** Normalizes absent grouped values to empty submission groups. */
  public SubmissionCommand {
    requestMetadata = requestMetadata == null ? RequestMetadata.empty() : requestMetadata;
    orderDetails = orderDetails == null ? OrderDetails.empty() : orderDetails;
  }

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

  /** Groups transport and order identity values for a submission request. */
  public record RequestIdentity(CommandId commandId, OrderId orderId, AccountId accountId) {
    /** Normalizes absent identity values to empty domain values. */
    public RequestIdentity {
      commandId = commandId == null ? CommandId.empty() : commandId;
      orderId = orderId == null ? OrderId.empty() : orderId;
      accountId = accountId == null ? AccountId.empty() : accountId;
    }

    /** Returns an empty request identity. */
    public static RequestIdentity empty() {
      return new RequestIdentity(CommandId.empty(), OrderId.empty(), AccountId.empty());
    }

    /** Returns whether every request identity value is blank. */
    public boolean hasNoPayloadFields() {
      return commandId.isBlank() && orderId.isBlank() && accountId.isBlank();
    }
  }

  /** Groups FIX session and client-order identity values for a submission request. */
  public record FixIdentity(
      SenderCompId senderCompId,
      TargetCompId targetCompId,
      ClOrdId clOrdId,
      OrigClOrdId origClOrdId) {
    /** Normalizes absent FIX identity values to empty domain values. */
    public FixIdentity {
      senderCompId = senderCompId == null ? SenderCompId.empty() : senderCompId;
      targetCompId = targetCompId == null ? TargetCompId.empty() : targetCompId;
      clOrdId = clOrdId == null ? ClOrdId.empty() : clOrdId;
      origClOrdId = origClOrdId == null ? OrigClOrdId.empty() : origClOrdId;
    }

    /** Returns an empty FIX identity. */
    public static FixIdentity empty() {
      return new FixIdentity(
          SenderCompId.empty(), TargetCompId.empty(), ClOrdId.empty(), OrigClOrdId.empty());
    }

    /** Returns whether every FIX identity value is blank. */
    public boolean hasNoPayloadFields() {
      return senderCompId.isBlank()
          && targetCompId.isBlank()
          && clOrdId.isBlank()
          && origClOrdId.isBlank();
    }
  }

  /** Groups the request identity, FIX identity, and optional trading day. */
  public record RequestMetadata(
      RequestIdentity identity, FixIdentity fixIdentity, LocalDate tradingDay) {
    /** Normalizes absent identity groups to empty values. */
    public RequestMetadata {
      identity = identity == null ? RequestIdentity.empty() : identity;
      fixIdentity = fixIdentity == null ? FixIdentity.empty() : fixIdentity;
    }

    /**
     * Creates metadata from the legacy v1 wire identity fields.
     *
     * @param commandId the transport-level command identifier
     * @param orderId the internal or client-visible order identifier
     * @param accountId the account that owns the submission
     * @param senderCompId the FIX SenderCompID
     * @param targetCompId the FIX TargetCompID
     * @param clOrdId the FIX ClOrdID
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
      this(
          new RequestIdentity(
              new CommandId(commandId), new OrderId(orderId), new AccountId(accountId)),
          new FixIdentity(
              new SenderCompId(senderCompId),
              new TargetCompId(targetCompId),
              new ClOrdId(clOrdId),
              new OrigClOrdId(origClOrdId)),
          null);
    }

    /** Returns empty request metadata. */
    public static RequestMetadata empty() {
      return new RequestMetadata(RequestIdentity.empty(), FixIdentity.empty(), null);
    }

    /** Returns whether metadata carries no identity or trading-day values. */
    public boolean hasNoPayloadFields() {
      return identity.hasNoPayloadFields()
          && fixIdentity.hasNoPayloadFields()
          && tradingDay == null;
    }
  }

  /** Groups the order-specific payload fields for a submission payload. */
  public record OrderDetails(
      String symbol,
      Side side,
      Quantity quantity,
      Price price,
      OrderType orderType,
      TimeInForce tif) {
    /** Normalizes absent order values to empty domain values. */
    public OrderDetails {
      symbol = nullToEmpty(symbol);
      side = side == null ? Side.SIDE_UNSPECIFIED : side;
      quantity = quantity == null ? Quantity.empty() : quantity;
      price = price == null ? Price.empty() : price;
      orderType = orderType == null ? OrderType.ORDER_TYPE_UNSPECIFIED : orderType;
      tif = tif == null ? TimeInForce.TIME_IN_FORCE_UNSPECIFIED : tif;
    }

    /** Creates order details from legacy v1 wire values. */
    public OrderDetails(
        String symbol,
        Side side,
        String quantity,
        String price,
        OrderType orderType,
        TimeInForce tif) {
      this(symbol, side, new Quantity(quantity), new Price(price), orderType, tif);
    }

    /** Returns empty order details. */
    public static OrderDetails empty() {
      return new OrderDetails(
          "",
          Side.SIDE_UNSPECIFIED,
          Quantity.empty(),
          Price.empty(),
          OrderType.ORDER_TYPE_UNSPECIFIED,
          TimeInForce.TIME_IN_FORCE_UNSPECIFIED);
    }

    /** Returns whether every order value is blank or unspecified. */
    public boolean hasNoPayloadFields() {
      return symbol.isBlank()
          && side == Side.SIDE_UNSPECIFIED
          && quantity.isBlank()
          && price.isBlank()
          && orderType == OrderType.ORDER_TYPE_UNSPECIFIED
          && tif == TimeInForce.TIME_IN_FORCE_UNSPECIFIED;
    }
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
    return requestMetadata.hasNoPayloadFields() && orderDetails.hasNoPayloadFields();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
