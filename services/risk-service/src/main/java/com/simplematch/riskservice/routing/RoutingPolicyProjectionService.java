package com.simplematch.riskservice.routing;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns strict decoding and atomic activation of Risk's local Routing Policy projection. */
public final class RoutingPolicyProjectionService implements RoutingPolicyProjector {
  private final RoutingPolicyProjectionRepository repository;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  /** Creates the projection service with one explicit local transaction boundary. */
  public RoutingPolicyProjectionService(
      RoutingPolicyProjectionRepository repository,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transaction template");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Decodes, validates, stages, and activates one complete policy publication.
   *
   * @param payload serialized shared Routing Policy protobuf
   * @return durable projection identity and duplicate indication
   */
  @Override
  public RoutingPolicyProjectionResult project(byte[] payload) {
    final RoutingPolicyProjection projection = RoutingPolicyProjectionDecoder.decode(payload);
    final RoutingPolicyProjectionResult result =
        transactionTemplate.execute(status -> projectInTransaction(projection));
    if (result == null) {
      throw new IllegalStateException("routing policy projection transaction returned null");
    }
    return result;
  }

  private RoutingPolicyProjectionResult projectInTransaction(RoutingPolicyProjection projection) {
    final UUID policyId = projection.identity().routingPolicyId();
    final var existing = repository.findById(policyId);
    if (existing.isPresent()) {
      if (!existing.orElseThrow().equals(projection)) {
        throw new RoutingPolicyProjectionValidationException(
            "routing policy id is already bound to different content");
      }
      return new RoutingPolicyProjectionResult(policyId, true);
    }
    try {
      repository.insertStaged(projection, clock.instant());
    } catch (DuplicateKeyException exception) {
      throw new RoutingPolicyProjectionValidationException(
          "routing policy projection conflicts with existing local state", exception);
    }
    repository.activate(policyId);
    return new RoutingPolicyProjectionResult(policyId, false);
  }
}
