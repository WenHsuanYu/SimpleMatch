package com.simplematch.tools.queryevidence;

import static java.util.Map.entry;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;

/** Correlates the public IOC submission with terminal processing and durable delivery evidence. */
final class ActiveProcessingEvidence {
  private static final Map<String, Object> EXPECTED =
      Map.ofEntries(
          entry("/status", "PROVEN"),
          entry("/queryOutage/queryPodCount", 0),
          entry("/gatewayOpen/accepted", true),
          entry("/gatewayOpen/gateState", "OPEN"),
          entry("/timeInForce", "3"),
          entry("/fixSubmission/timeInForce", "3"),
          entry("/fixSubmission/execType", "A"),
          entry("/fixSubmission/ordStatus", "A"),
          entry("/fixSubmission/terminalExecType", "4"),
          entry("/fixSubmission/terminalOrdStatus", "4"),
          entry("/riskAdmission/state", "ACCEPTED"),
          entry("/matchingEvent/topic", "matching.events"),
          entry("/matchingEvent/eventType", "MATCHING_EVENT_TYPE_ORDER_CANCELLED"),
          entry("/observerVerdict/status", "PASS"),
          entry("/orderProjection/status", "CANCELLED"),
          entry("/accountReservation/status", "RESERVATION_STATUS_RELEASED"),
          entry("/accountReservation/remainingQuantity", 0),
          entry("/accountReservation/reservedNotional", 0),
          entry("/fixDeliveryIntent/status", "SENT"),
          entry("/fixDeliveryIntent/execType", "4"),
          entry("/fixDeliveryIntent/ordStatus", "4"),
          entry("/marketData/recoveryState", "READY"),
          entry("/marketData/inboxCount", 1),
          entry("/exactInboxCounts/persistence", 1),
          entry("/exactInboxCounts/account", 1),
          entry("/exactInboxCounts/quickfix", 1),
          entry("/exactInboxCounts/marketData", 1),
          entry("/consumerState/persistenceQuarantines", 0),
          entry("/consumerState/accountQuarantines", 0),
          entry("/consumerState/quickfixQuarantines", 0),
          entry("/consumerState/persistenceQuarantineHistory", 0),
          entry("/consumerState/accountQuarantineHistory", 0),
          entry("/consumerState/quickfixQuarantineHistory", 0),
          entry("/consumerState/quickfixPendingIntents", 0),
          entry("/consumerState/activeMatchingOrders", 0),
          entry("/consumerState/marketDataDeadLetters", 0));
  private static final Map<String, String> IDENTITY_LINKS =
      Map.ofEntries(
          entry("/fixSubmission/accountId", "/riskAdmission/accountId"),
          entry("/fixSubmission/clOrdId", "/riskAdmission/clOrdId"),
          entry("/matchingEvent/partition", "/riskAdmission/routingPartition"),
          entry("/matchingEvent/sourceCommandId", "/riskAdmission/commandId"),
          entry("/matchingEvent/orderId", "/riskAdmission/orderId"),
          entry("/orderProjection/orderId", "/riskAdmission/orderId"),
          entry("/orderProjection/lastEventId", "/matchingEvent/eventId"),
          entry("/accountReservation/orderId", "/riskAdmission/orderId"),
          entry("/accountReservation/accountId", "/riskAdmission/accountId"),
          entry("/fixDeliveryIntent/eventId", "/matchingEvent/eventId"),
          entry("/marketData/partition", "/matchingEvent/partition"));

  boolean isHealthy(JsonNode active, JsonNode queryOutage) {
    return JsonChecks.number(queryOutage.path("queryPodCount"), 0)
        && JsonChecks.values(active, EXPECTED)
        && IDENTITY_LINKS.entrySet().stream()
            .allMatch(
                link -> JsonChecks.equal(active.at(link.getKey()), active.at(link.getValue())))
        && validTiming(active)
        && validEvent(active)
        && validMarketProgress(active);
  }

  private boolean validTiming(JsonNode active) {
    final JsonNode start = active.path("startedAtEpochMs");
    final JsonNode end = active.path("completedAtEpochMs");
    final JsonNode timeout = active.path("timeoutSeconds");
    return JsonChecks.number(start, value -> value.signum() >= 0)
        && JsonChecks.number(end, value -> value.compareTo(start.decimalValue()) >= 0)
        && JsonChecks.number(
            timeout, value -> value.signum() > 0 && value.compareTo(BigDecimal.valueOf(300)) <= 0)
        && JsonChecks.number(
            active.path("elapsedMilliseconds"),
            value ->
                value.compareTo(end.decimalValue().subtract(start.decimalValue())) == 0
                    && value.compareTo(timeout.decimalValue().multiply(BigDecimal.valueOf(1000)))
                        <= 0)
        && JsonChecks.number(
            active.at("/fixSubmission/sentAtEpochMs"),
            value ->
                value.compareTo(start.decimalValue()) >= 0
                    && value.compareTo(end.decimalValue()) <= 0);
  }

  private boolean validEvent(JsonNode active) {
    final JsonNode start = active.at("/matchingEvent/startOffset");
    return JsonChecks.nonemptyText(active.at("/fixSubmission/orderId"))
        && active.at("/fixSubmission/orderId").textValue().startsWith("O-")
        && JsonChecks.number(
            active.at("/riskAdmission/routingPartition"),
            value -> value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(14)) <= 0)
        && JsonChecks.number(start, value -> value.signum() >= 0)
        && JsonChecks.number(
            active.at("/matchingEvent/offset"), value -> value.compareTo(start.decimalValue()) >= 0)
        && JsonChecks.nonemptyText(active.at("/matchingEvent/eventId"))
        && active.at("/matchingEvent/eventId").textValue().matches("[0-9a-f]{64}");
  }

  private boolean validMarketProgress(JsonNode active) {
    final JsonNode offset = active.at("/matchingEvent/offset");
    return JsonChecks.number(
            active.at("/marketData/lastProcessedOffset"),
            value -> value.compareTo(offset.decimalValue()) >= 0)
        && JsonChecks.elements(active.at("/consumerState/marketDataProgress")).stream()
            .anyMatch(
                progress ->
                    JsonChecks.equal(
                            progress.path("partition_id"), active.at("/matchingEvent/partition"))
                        && JsonChecks.number(
                            progress.path("last_processed_offset"),
                            value -> value.compareTo(offset.decimalValue()) >= 0)
                        && JsonChecks.text(progress.path("recovery_state"), "READY"));
  }
}
