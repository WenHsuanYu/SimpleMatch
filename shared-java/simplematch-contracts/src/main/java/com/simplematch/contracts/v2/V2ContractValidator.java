package com.simplematch.contracts.v2;

import com.simplematch.contracts.common.v2.Currency;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.OrderType;
import com.simplematch.contracts.common.v2.SessionState;
import com.simplematch.contracts.common.v2.Side;
import com.simplematch.contracts.orders.v2.CancelOrderCommand;
import com.simplematch.contracts.orders.v2.NewOrderCommand;

/** Validates values before they cross the v2 command-contract seam. */
public final class V2ContractValidator {
  /** Validates the common envelope that every v2 command and event carries. */
  public void validate(EventMetadata metadata) {
    if (metadata == null) {
      throw new DomainValidationException("metadata is required");
    }
    if (!"v2".equals(metadata.getSchemaVersion())) {
      throw new DomainValidationException("schema_version must be v2");
    }
    V2Identifiers.EventId.parse(metadata.getEventId());
    if (metadata.getCreatedAtUnixMs() <= 0) {
      throw new DomainValidationException("created_at_unix_ms must be positive");
    }
    requiredText(metadata.getSourceService(), "source_service");
    V2Identifiers.CommandId.parse(metadata.getCorrelationId());
    if (!metadata.getCausationId().isBlank()) {
      V2Identifiers.EventId.parse(metadata.getCausationId());
    }
  }

  /** Validates a new-order command before durable admission. */
  public void validate(NewOrderCommand command) {
    if (command == null) {
      throw new DomainValidationException("new order command is required");
    }
    validate(command.getMetadata());
    V2Identifiers.CommandId.parse(command.getCommandId());
    V2Identifiers.OrderId.parse(command.getOrderId());
    V2Identifiers.AccountId.parse(command.getAccountId());
    validateInstrument(command.getInstrument().getSymbol(), command.getInstrument().getVenueMic());
    if (command.getSide() == Side.SIDE_UNSPECIFIED) {
      throw new DomainValidationException("side is required");
    }
    new ShareQuantity(command.getQuantity().getShares());
    validatePrice(command);
    if (command.getTif() == com.simplematch.contracts.common.v2.TimeInForce.TIME_IN_FORCE_UNSPECIFIED) {
      throw new DomainValidationException("tif is required");
    }
    if (command.getCurrency() != Currency.CURRENCY_TWD) {
      throw new DomainValidationException("currency must be TWD");
    }
    TradingDay.parse(command.getTradingDay().getIsoDate());
    if (command.getSessionState() == SessionState.SESSION_STATE_UNSPECIFIED) {
      throw new DomainValidationException("session_state is required");
    }
    if (!command.getRoutingSnapshotId().isBlank()) {
      V2Identifiers.SnapshotId.parse(command.getRoutingSnapshotId());
    }
    if (command.hasEstimatedNotional() && command.getEstimatedNotional().getUnits() <= 0) {
      throw new DomainValidationException("estimated_notional must be positive when present");
    }
    validateFixIdentity(command.getSenderCompId(), command.getTargetCompId(), command.getClOrdId(), "");
  }

  /** Validates a cancel-order command before durable admission. */
  public void validate(CancelOrderCommand command) {
    if (command == null) {
      throw new DomainValidationException("cancel order command is required");
    }
    validate(command.getMetadata());
    V2Identifiers.CommandId.parse(command.getCommandId());
    V2Identifiers.OrderId.parse(command.getOrderId());
    V2Identifiers.AccountId.parse(command.getAccountId());
    validateInstrument(command.getInstrument().getSymbol(), command.getInstrument().getVenueMic());
    TradingDay.parse(command.getTradingDay().getIsoDate());
    if (command.getSessionState() == SessionState.SESSION_STATE_UNSPECIFIED) {
      throw new DomainValidationException("session_state is required");
    }
    validateFixIdentity(
        command.getSenderCompId(),
        command.getTargetCompId(),
        command.getClOrdId(),
        command.getOrigClOrdId());
  }

  private void validatePrice(NewOrderCommand command) {
    if (command.getOrderType() == OrderType.ORDER_TYPE_LIMIT) {
      new TwdPrice(command.getLimitPrice().getUnits());
      return;
    }
    if (command.getOrderType() != OrderType.ORDER_TYPE_MARKET) {
      throw new DomainValidationException("order_type is required");
    }
    if (command.hasLimitPrice()) {
      throw new DomainValidationException("market orders must not carry limit_price");
    }
  }

  private void validateInstrument(String symbol, String venueMic) {
    requiredText(symbol, "instrument.symbol");
    VenueMic.parse(venueMic);
  }

  private void validateFixIdentity(
      String senderCompId,
      String targetCompId,
      String clOrdId,
      String origClOrdId) {
    requiredText(senderCompId, "sender_comp_id");
    requiredText(targetCompId, "target_comp_id");
    requiredText(clOrdId, "cl_ord_id");
    if (origClOrdId == null) {
      throw new DomainValidationException("orig_cl_ord_id must not be null");
    }
  }

  private void requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new DomainValidationException(fieldName + " is required");
    }
  }
}
