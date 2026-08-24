package com.simplematch.contracts.matching.runtime.v1;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/** Validates the final-event wire contract before a critical consumer applies business effects. */
final class FinalMatchingEventValidator {
  private static final int FINAL_SCHEMA_VERSION = 1;
  private static final int FINAL_IDENTITY_VERSION = 1;
  private static final int FIXED_PARTITION_COUNT = 15;

  private FinalMatchingEventValidator() {}

  static void validate(MatchingEvent event) {
    FinalMatchingEventValidationRules.require(
        event.getSchemaVersion() == FINAL_SCHEMA_VERSION,
        "unsupported final matching event schema");
    FinalMatchingEventValidationRules.require(
        event.getIdentityVersion() == FINAL_IDENTITY_VERSION,
        "unsupported final matching event identity version");
    FinalMatchingEventValidationRules.requireSha256(event.getEventId(), "eventId");
    FinalMatchingEventValidationRules.requireUuid(event.getSourceCommandId(), "sourceCommandId");
    FinalMatchingEventValidationRules.require(
        !event.getTradingSessionId().isBlank(), "tradingSessionId must not be blank");
    FinalMatchingEventValidationRules.require(
        !event.getRoutingAlgorithmVersion().isBlank(), "routingAlgorithmVersion must not be blank");
    FinalMatchingEventValidationRules.require(
        event.getPartitionId() >= 0 && event.getPartitionId() < FIXED_PARTITION_COUNT,
        "partitionId must be between 0 and 14");
    FinalMatchingEventValidationRules.require(
        event.getSourceInputOffset() >= 0, "sourceInputOffset must not be negative");
    FinalMatchingEventValidationRules.require(
        event.getOutputIndex() >= 0, "outputIndex must not be negative");
    LocalDate.parse(event.getArtifactIdentity().getTradingDay());
    FinalMatchingEventValidationRules.requireCanonicalHex(
        event.getArtifactIdentity().getContentSha256(), "artifact content SHA-256");

    final UUID sourceCommandId = UUID.fromString(event.getSourceCommandId());
    final byte[] expectedEventId =
        MatchingEventIdentityV1.eventId(
            event.getTradingSessionId(),
            event.getPartitionId(),
            sourceCommandId,
            event.getOutputIndex());
    FinalMatchingEventValidationRules.require(
        Arrays.equals(expectedEventId, event.getEventId().toByteArray()),
        "eventId does not match the deterministic Matching identity");

    switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_RESTED -> validateRested(event);
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED -> validateTrade(event, sourceCommandId);
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED -> validateCancelled(event);
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED -> validateExpired(event);
      default -> throw new IllegalArgumentException("final matching event type is required");
    }
  }

  private static void validateRested(MatchingEvent event) {
    FinalMatchingEventValidationRules.require(
        event.getEventCase() == MatchingEvent.EventCase.ORDER_RESTED,
        "rested event payload mismatch");
    FinalMatchingEventValidationFields.validateRested(event.getOrderRested());
  }

  private static void validateTrade(MatchingEvent event, UUID sourceCommandId) {
    FinalMatchingEventValidationRules.require(
        event.getEventCase() == MatchingEvent.EventCase.TRADE_EXECUTED,
        "trade event payload mismatch");
    final TradeExecuted trade = event.getTradeExecuted();
    FinalMatchingEventValidationFields.validateTrade(trade);
    FinalMatchingEventValidationRules.require(
        trade.getMatchIndex() >= 0, "trade matchIndex must not be negative");
    final byte[] expectedTradeId =
        MatchingEventIdentityV1.tradeId(
            event.getTradingSessionId(),
            event.getPartitionId(),
            sourceCommandId,
            trade.getMatchIndex());
    FinalMatchingEventValidationRules.require(
        Arrays.equals(expectedTradeId, trade.getTradeId().toByteArray()),
        "tradeId does not match the deterministic Matching identity");
  }

  private static void validateCancelled(MatchingEvent event) {
    FinalMatchingEventValidationRules.require(
        event.getEventCase() == MatchingEvent.EventCase.ORDER_CANCELLED,
        "cancelled event payload mismatch");
    FinalMatchingEventValidationFields.validateTerminal(event.getOrderCancelled());
  }

  private static void validateExpired(MatchingEvent event) {
    FinalMatchingEventValidationRules.require(
        event.getEventCase() == MatchingEvent.EventCase.ORDER_EXPIRED,
        "expired event payload mismatch");
    FinalMatchingEventValidationFields.validateTerminal(event.getOrderExpired());
  }
}
