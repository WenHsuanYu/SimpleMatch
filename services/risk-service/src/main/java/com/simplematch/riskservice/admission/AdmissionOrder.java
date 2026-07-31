package com.simplematch.riskservice.admission;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Validated order facts used by risk admission and account authority.
 *
 * @param instrument the instrument identity
 * @param characteristics the order characteristics
 * @param tradingDay the business trading day
 */
public record AdmissionOrder(
    Instrument instrument, Characteristics characteristics, LocalDate tradingDay) {
  /** Requires complete order facts. */
  public AdmissionOrder {
    instrument = Objects.requireNonNull(instrument, "instrument");
    characteristics = Objects.requireNonNull(characteristics, "characteristics");
    tradingDay = Objects.requireNonNull(tradingDay, "tradingDay");
  }

  /** Instrument identity used for validation and routing. */
  public record Instrument(Symbol symbol, VenueMic venueMic) {
    /** Requires both symbol and venue. */
    public Instrument {
      symbol = Objects.requireNonNull(symbol, "symbol");
      venueMic = Objects.requireNonNull(venueMic, "venueMic");
    }
  }

  /** Order characteristics needed by admission and reservation. */
  public record Characteristics(
      SideCode side,
      Quantity quantity,
      LimitPriceUnits limitPrice,
      OrderTypeCode orderType,
      TimeInForceCode timeInForce) {
    /** Requires every validated characteristic. */
    public Characteristics {
      side = Objects.requireNonNull(side, "side");
      quantity = Objects.requireNonNull(quantity, "quantity");
      limitPrice = Objects.requireNonNull(limitPrice, "limitPrice");
      orderType = Objects.requireNonNull(orderType, "orderType");
      timeInForce = Objects.requireNonNull(timeInForce, "timeInForce");
    }
  }

  /** Returns whether this order represents a cancellation admission. */
  public boolean isCancellation() {
    return "CANCEL".equals(characteristics.orderType().value());
  }

  /** Validated instrument symbol. */
  public record Symbol(String value) {
    /** Requires a nonblank symbol. */
    public Symbol {
      value = requireNonBlank(value, "symbol");
    }
  }

  /** Validated venue market identifier code. */
  public record VenueMic(String value) {
    /** Requires a nonblank venue code. */
    public VenueMic {
      value = requireNonBlank(value, "venue_mic");
    }
  }

  /** Validated side code independent of the transport enum. */
  public record SideCode(String value) {
    /** Requires a nonblank side code. */
    public SideCode {
      value = requireNonBlank(value, "side");
    }
  }

  /** Positive share quantity admitted for the order. */
  public record Quantity(long value) {
    /** Requires a positive quantity. */
    public Quantity {
      if (value <= 0) {
        throw new IllegalArgumentException("quantity must be positive");
      }
    }
  }

  /** Optional positive fixed-point limit-price units. */
  public record LimitPriceUnits(Long value) {
    /** Requires a positive value when a limit price is present. */
    public LimitPriceUnits {
      if (value != null && value <= 0) {
        throw new IllegalArgumentException("limit_price_units must be positive when present");
      }
    }
  }

  /** Validated order-type code independent of the transport enum. */
  public record OrderTypeCode(String value) {
    /** Requires a nonblank order-type code. */
    public OrderTypeCode {
      value = requireNonBlank(value, "order_type");
    }
  }

  /** Validated time-in-force code independent of the transport enum. */
  public record TimeInForceCode(String value) {
    /** Requires a nonblank time-in-force code. */
    public TimeInForceCode {
      value = requireNonBlank(value, "time_in_force");
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
