package com.simplematch.accountservice.matching;

import com.simplematch.accountservice.kafka.AccountLifecycleApplier;
import com.simplematch.accountservice.store.JdbcFinalMatchingEventAccountInbox;
import com.simplematch.contracts.matching.runtime.v1.FinalMatchingEventEnvelope;
import com.simplematch.contracts.matching.runtime.v1.MatchingEvent;
import com.simplematch.contracts.matching.runtime.v1.TradeLeg;
import com.simplematch.contracts.matching.v1.ExecutionEvent;
import com.simplematch.contracts.matching.v1.ExecutionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adapts final Matching Event facts into the existing Account Authority transaction boundary. */
@Service
public class FinalMatchingEventAccountApplicationService
    implements FinalMatchingEventAccountHandler {
  private static final int TRANSACTION_TIMEOUT_SECONDS = 8;
  private static final int TWD_PRICE_SCALE = 4;

  private final JdbcFinalMatchingEventAccountInbox inbox;
  private final AccountLifecycleApplier accountLifecycleApplier;
  private final Clock clock;

  /** Creates the bounded adapter over Account's retained reservation aggregate API. */
  public FinalMatchingEventAccountApplicationService(
      JdbcFinalMatchingEventAccountInbox inbox,
      AccountLifecycleApplier accountLifecycleApplier,
      Clock clock) {
    this.inbox = inbox;
    this.accountLifecycleApplier = accountLifecycleApplier;
    this.clock = clock;
  }

  /**
   * Applies raw inbox, authority effects, Account outbox, and progress in one local transaction.
   */
  @Override
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public FinalMatchingEventAccountOutcome apply(
      FinalMatchingEventEnvelope envelope, int kafkaPartition, long kafkaOffset) {
    final FinalMatchingEventEnvelope finalEnvelope = Objects.requireNonNull(envelope, "envelope");
    final long now = clock.millis();
    if (!inbox.claim(finalEnvelope, now)) {
      inbox.recordProgress(kafkaPartition, kafkaOffset, now);
      return FinalMatchingEventAccountOutcome.DUPLICATE;
    }
    applyAuthorityEffects(finalEnvelope.event());
    inbox.recordProgress(kafkaPartition, kafkaOffset, now);
    return FinalMatchingEventAccountOutcome.APPLIED;
  }

  private void applyAuthorityEffects(MatchingEvent event) {
    switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_TRADE_EXECUTED -> {
        applyTradeLeg(event, event.getTradeExecuted().getMaker(), "maker");
        applyTradeLeg(event, event.getTradeExecuted().getTaker(), "taker");
      }
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          applyTerminal(
              event,
              event.getOrderCancelled().getOrderId(),
              event.getOrderCancelled().getAccountId());
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED ->
          applyTerminal(
              event, event.getOrderExpired().getOrderId(), event.getOrderExpired().getAccountId());
      case MATCHING_EVENT_TYPE_ORDER_RESTED -> {
        // The reservation has already been accepted; resting is intentionally account-neutral.
      }
      default -> throw new IllegalArgumentException("final Matching Event type is required");
    }
  }

  private void applyTradeLeg(MatchingEvent event, TradeLeg leg, String role) {
    final String executionId =
        FinalMatchingEventEnvelope.deterministicUuid(
                "simplematch.account-final-fill-v1", event.getEventId(), leg.getOrderId(), role)
            .toString();
    accountLifecycleApplier.applyMatchingExecution(
        ExecutionEvent.newBuilder()
            .setExecId(executionId)
            .setOrderId(leg.getOrderId())
            .setAccountId(leg.getAccountId())
            .setSymbol(event.getTradeExecuted().getInstrument().getSymbol())
            .setExecutionType(
                leg.getLeavesQuantityShares() == 0
                    ? ExecutionType.EXECUTION_TYPE_FILL
                    : ExecutionType.EXECUTION_TYPE_PARTIAL_FILL)
            .setFillQty(Long.toString(leg.getQuantityShares()))
            .setFillPx(twdPrice(leg.getPriceUnits()))
            .setCumQty(Long.toString(leg.getCumulativeQuantityShares()))
            .setLeavesQty(Long.toString(leg.getLeavesQuantityShares()))
            .setAveragePx(twdPrice(leg.getAveragePriceUnits()))
            .setText("MATCHING_TRADE")
            .build());
  }

  private void applyTerminal(MatchingEvent event, String orderId, String accountId) {
    final String executionId =
        FinalMatchingEventEnvelope.deterministicUuid(
                "simplematch.account-final-terminal-v1", event.getEventId(), orderId)
            .toString();
    accountLifecycleApplier.applyMatchingExecution(
        ExecutionEvent.newBuilder()
            .setExecId(executionId)
            .setOrderId(orderId)
            .setAccountId(accountId)
            .setSymbol(terminalSymbol(event))
            .setExecutionType(ExecutionType.EXECUTION_TYPE_CANCELED)
            .setText(terminalReason(event))
            .build());
  }

  private String terminalSymbol(MatchingEvent event) {
    return switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED ->
          event.getOrderCancelled().getInstrument().getSymbol();
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED -> event.getOrderExpired().getInstrument().getSymbol();
      default -> throw new IllegalArgumentException("terminal Matching Event type is required");
    };
  }

  private String terminalReason(MatchingEvent event) {
    return switch (event.getEventType()) {
      case MATCHING_EVENT_TYPE_ORDER_CANCELLED -> "MATCHING_CANCELLED";
      case MATCHING_EVENT_TYPE_ORDER_EXPIRED -> "MATCHING_EXPIRED";
      default -> throw new IllegalArgumentException("terminal Matching Event type is required");
    };
  }

  private String twdPrice(long priceUnits) {
    return BigDecimal.valueOf(priceUnits, TWD_PRICE_SCALE).toPlainString();
  }
}
