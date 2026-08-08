package com.simplematch.marketdatapublisher.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplematch.contracts.common.v2.EventMetadata;
import com.simplematch.contracts.common.v2.TradingDay;
import com.simplematch.contracts.common.v2.VenueInstrument;
import com.simplematch.marketdatapublisher.snapshot.InstrumentIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** Owns the local transaction that publishes one complete Market Reference routing policy. */
@RequiredArgsConstructor
public class RoutingPolicyApplicationService {
  /** Stable Kafka topic for versioned Market Reference routing policies. */
  public static final String ROUTING_POLICY_PUBLISHED_TOPIC = "market-reference.routing-policies";

  /** Keeps the ordered policy stream on one explicit partition. */
  public static final int ROUTING_POLICY_PARTITION = 0;

  private static final int TRANSACTION_TIMEOUT_SECONDS = 10;

  @NonNull private final RoutingPolicyRepository policies;
  @NonNull private final RoutingPolicyOutbox outbox;
  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final Clock clock;
  @NonNull private final Supplier<UUID> eventIds;

  /**
   * Publishes a complete policy and its binary outbox event as one local transaction.
   *
   * @param policy validated immutable policy to publish
   * @return the durable policy identity and duplicate indication
   * @throws RoutingPolicyPublicationFailure if event serialization or outbox persistence fails
   */
  @Transactional(
      timeout = TRANSACTION_TIMEOUT_SECONDS,
      rollbackFor = RoutingPolicyPublicationFailure.class)
  public RoutingPolicyPublicationResult publishRoutingPolicy(RoutingPolicy policy)
      throws RoutingPolicyPublicationFailure {
    Objects.requireNonNull(policy, "routing policy");
    final var existing = policies.findById(policy.identity().routingPolicyId());
    if (existing.isPresent()) {
      if (!existing.orElseThrow().equals(policy)) {
        throw new RoutingPolicyPublicationConflictException(
            "routing policy id is already bound to different content");
      }
      return result(policy, true);
    }
    policies.lockSourceSnapshot(
        policy.identity().sourceMarketSnapshotId(), policy.identity().tradingDay());
    validateIntradayContinuity(policy);
    final Instant publishedAt = clock.instant();
    try {
      policies.insert(policy, publishedAt);
    } catch (DuplicateKeyException exception) {
      throw new RoutingPolicyPublicationConflictException(
          "a conflicting routing policy publication already exists");
    }
    outbox.insert(outboxRecord(policy, publishedAt));
    return result(policy, false);
  }

  private void validateIntradayContinuity(RoutingPolicy policy) {
    final List<RoutingPolicy> existingPolicies =
        policies.findAllForTradingDayForUpdate(policy.identity().tradingDay());
    if (existingPolicies.isEmpty()) {
      return;
    }
    final RoutingPolicy latestPolicy =
        existingPolicies.stream()
            .max(
                Comparator.comparing(existing -> existing.effectiveInterval().effectiveFrom()))
            .orElseThrow();
    if (policy
        .effectiveInterval()
        .effectiveFrom()
        .isBefore(latestPolicy.effectiveInterval().effectiveFrom())) {
      throw new RoutingPolicyPublicationConflictException(
          "routing policy effective intervals must be published in effective order");
    }
    if (existingPolicies.stream()
        .anyMatch(existing -> existing.effectiveInterval().overlaps(policy.effectiveInterval()))) {
      throw new RoutingPolicyPublicationConflictException(
          "routing policy effective interval overlaps an existing policy");
    }

    final Map<InstrumentIdentity, Integer> historicalAssignments = new LinkedHashMap<>();
    for (RoutingPolicy existing : existingPolicies) {
      existing
          .assignments()
          .forEach(
              assignment -> {
                final Integer previousPartition =
                    historicalAssignments.putIfAbsent(
                        assignment.instrument(), assignment.routingPartition());
                if (previousPartition != null
                    && previousPartition.intValue() != assignment.routingPartition()) {
                  throw new RoutingPolicyPublicationConflictException(
                      "existing routing policy history contains an instrument reassignment");
                }
              });
    }
    final Map<InstrumentIdentity, Integer> candidateAssignments = new LinkedHashMap<>();
    policy
        .assignments()
        .forEach(
            assignment ->
                candidateAssignments.put(assignment.instrument(), assignment.routingPartition()));
    historicalAssignments.forEach(
        (instrument, historicalPartition) -> {
          final Integer candidatePartition = candidateAssignments.get(instrument);
          if (candidatePartition == null) {
            throw new RoutingPolicyPublicationConflictException(
                "routing policy does not carry forward instrument: " + instrument);
          }
          if (candidatePartition.intValue() != historicalPartition.intValue()) {
            throw new RoutingPolicyPublicationConflictException(
                "routing policy reassigns instrument: " + instrument);
          }
        });
  }

  private RoutingPolicyPublicationResult result(RoutingPolicy policy, boolean duplicate) {
    return new RoutingPolicyPublicationResult(
        policy.identity().routingPolicyId(), policy.identity().tradingDay(), duplicate);
  }

  private RoutingPolicyOutboxRecord outboxRecord(RoutingPolicy policy, Instant publishedAt)
      throws RoutingPolicyPublicationFailure {
    final UUID eventId = eventIds.get();
    final byte[] payload = serializePolicy(eventId, policy, publishedAt);
    final String payloadType =
        com.simplematch.contracts.routing.v2.RoutingPolicy.getDescriptor().getFullName();
    return new RoutingPolicyOutboxRecord(
        new RoutingPolicyOutboxRecord.EventIdentity(eventId),
        new RoutingPolicyOutboxRecord.Destination(
            ROUTING_POLICY_PUBLISHED_TOPIC,
            policy.identity().tradingDay().toString(),
            ROUTING_POLICY_PARTITION),
        new RoutingPolicyOutboxRecord.Payload(
            payload, payloadType, serializeHeaders(eventId, payloadType)),
        new RoutingPolicyOutboxRecord.AggregateReference(
            "routing_policy", policy.identity().routingPolicyId().toString()),
        publishedAt.toEpochMilli());
  }

  private byte[] serializePolicy(UUID eventId, RoutingPolicy policy, Instant publishedAt) {
    final EventMetadata metadata =
        EventMetadata.newBuilder()
            .setSchemaVersion("v2")
            .setEventId(eventId.toString())
            .setCreatedAtUnixMs(publishedAt.toEpochMilli())
            .setSourceService("marketdata-publisher")
            .setCorrelationId(policy.identity().routingPolicyId().toString())
            .build();
    final var builder =
        com.simplematch.contracts.routing.v2.RoutingPolicy.newBuilder()
            .setMetadata(metadata)
            .setRoutingPolicyId(policy.identity().routingPolicyId().toString())
            .setSourceMarketSnapshotId(policy.identity().sourceMarketSnapshotId().toString())
            .setTradingDay(
                TradingDay.newBuilder()
                    .setIsoDate(policy.identity().tradingDay().toString())
                    .build())
            .setEffectiveFromUnixMs(policy.effectiveInterval().effectiveFrom().toEpochMilli())
            .setEffectiveUntilUnixMs(policy.effectiveInterval().effectiveUntil().toEpochMilli())
            .setOrdersValidatedPartitionCount(policy.ordersValidatedPartitionCount());
    policy
        .assignments()
        .forEach(
            assignment ->
                builder.addAssignments(
                    com.simplematch.contracts.routing.v2.InstrumentRoutingAssignment.newBuilder()
                        .setInstrument(
                            VenueInstrument.newBuilder()
                                .setSymbol(assignment.instrument().symbol())
                                .setVenueMic(assignment.instrument().venueMic())
                                .build())
                        .setRoutingPartition(assignment.routingPartition())
                        .build()));
    return builder.build().toByteArray();
  }

  private String serializeHeaders(UUID eventId, String payloadType)
      throws RoutingPolicyPublicationFailure {
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("content_type", "application/x-protobuf");
    headers.put("event_id", eventId.toString());
    headers.put("payload_type", payloadType);
    try {
      return objectMapper.writeValueAsString(headers);
    } catch (JsonProcessingException exception) {
      throw new RoutingPolicyPublicationFailure(
          "failed to serialize routing policy publication headers", exception);
    }
  }
}
