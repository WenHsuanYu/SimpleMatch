package com.simplematch.riskservice.routing;

import com.google.protobuf.InvalidProtocolBufferException;
import com.simplematch.contracts.common.v2.EventMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Decodes and strictly validates the shared protobuf Routing Policy publication. */
public final class RoutingPolicyProjectionDecoder {
  private RoutingPolicyProjectionDecoder() {}

  /**
   * Decodes a complete Market Reference policy without consulting a remote service.
   *
   * @param payload serialized {@code simplematch.routing.v2.RoutingPolicy}
   * @return validated Risk-local projection
   * @throws RoutingPolicyProjectionValidationException when the payload is malformed or incomplete
   */
  public static RoutingPolicyProjection decode(byte[] payload) {
    Objects.requireNonNull(payload, "routing policy payload");
    final com.simplematch.contracts.routing.v2.RoutingPolicy policy = parse(payload);
    validateIdentityFields(policy);
    final RoutingPolicyProjectionIdentity identity =
        new RoutingPolicyProjectionIdentity(
            parseUuid(policy.getRoutingPolicyId(), "routing policy id"),
            parseUuid(policy.getSourceMarketSnapshotId(), "source market snapshot id"),
            parseTradingDay(policy.getTradingDay().getIsoDate()));
    final List<RoutingPolicyAssignment> assignments = new ArrayList<>();
    policy
        .getAssignmentsList()
        .forEach(
            assignment ->
                assignments.add(
                    new RoutingPolicyAssignment(
                        new RoutingInstrument(
                            assignment.getInstrument().getSymbol(),
                            assignment.getInstrument().getVenueMic()),
                        assignment.getRoutingPartition())));
    try {
      return new RoutingPolicyProjection(
          identity,
          new RoutingPolicyProjectionInterval(
              Instant.ofEpochMilli(policy.getEffectiveFromUnixMs()),
              Instant.ofEpochMilli(policy.getEffectiveUntilUnixMs())),
          new RoutingPolicyPartitionTopology(policy.getOrdersValidatedPartitionCount()),
          assignments);
    } catch (RoutingPolicyProjectionValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy payload violates projection invariants", exception);
    }
  }

  private static com.simplematch.contracts.routing.v2.RoutingPolicy parse(byte[] payload) {
    try {
      return com.simplematch.contracts.routing.v2.RoutingPolicy.parseFrom(payload);
    } catch (InvalidProtocolBufferException exception) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy payload is not valid protobuf", exception);
    }
  }

  private static void validateIdentityFields(
      com.simplematch.contracts.routing.v2.RoutingPolicy policy) {
    validateMetadata(policy);
    validateTradingDay(policy);
    validateAssignments(policy);
  }

  private static void validateMetadata(
      com.simplematch.contracts.routing.v2.RoutingPolicy policy) {
    if (!policy.hasMetadata()) {
      throw new RoutingPolicyProjectionValidationException("routing policy metadata is required");
    }
    final EventMetadata metadata = policy.getMetadata();
    requireText(metadata.getSchemaVersion(), "metadata schema version", "v2");
    requireText(metadata.getSourceService(), "metadata source service", "marketdata-publisher");
    parseUuid(metadata.getEventId(), "metadata event id");
    if (metadata.getCreatedAtUnixMs() <= 0) {
      throw new RoutingPolicyProjectionValidationException(
          "metadata created timestamp must be positive");
    }
    final UUID policyId = parseUuid(policy.getRoutingPolicyId(), "routing policy id");
    final UUID correlationId = parseUuid(metadata.getCorrelationId(), "metadata correlation id");
    if (!policyId.equals(correlationId)) {
      throw new RoutingPolicyProjectionValidationException(
          "metadata correlation id must equal routing policy id");
    }
  }

  private static void validateTradingDay(
      com.simplematch.contracts.routing.v2.RoutingPolicy policy) {
    if (!policy.hasTradingDay() || policy.getTradingDay().getIsoDate().isBlank()) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy trading day is required");
    }
  }

  private static void validateAssignments(
      com.simplematch.contracts.routing.v2.RoutingPolicy policy) {
    if (policy.getAssignmentsCount() == 0) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy assignment set must not be empty");
    }
  }

  private static void requireText(String value, String fieldName, String expected) {
    if (!expected.equals(value)) {
      throw new RoutingPolicyProjectionValidationException(
          fieldName + " must equal " + expected);
    }
  }

  private static UUID parseUuid(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new RoutingPolicyProjectionValidationException(fieldName + " is required");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new RoutingPolicyProjectionValidationException(
          fieldName + " must be a UUID", exception);
    }
  }

  private static LocalDate parseTradingDay(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy trading day must be ISO-8601", exception);
    }
  }
}
