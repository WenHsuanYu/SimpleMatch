package com.simplematch.riskservice.admission;

import com.simplematch.riskservice.routing.RoutingInstrument;
import com.simplematch.riskservice.routing.RoutingPolicyProjection;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionRepository;
import com.simplematch.riskservice.routing.RoutingPolicyProjectionValidationException;
import java.time.Instant;
import java.util.Objects;

/** Resolves Admission routes from Risk's complete local Routing Policy projection. */
public final class LocalAdmissionRoutingPolicyResolver
    implements AdmissionRoutingPolicyResolver {
  private final RoutingPolicyProjectionRepository repository;

  /** Creates the resolver over the durable local policy projection. */
  public LocalAdmissionRoutingPolicyResolver(RoutingPolicyProjectionRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public AdmissionDeliveryRoute resolve(AdmissionCommand command, Instant at) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(at, "at");
    final RoutingPolicyProjection projection =
        repository
            .findApplicable(command.order().tradingDay(), at)
            .orElseThrow(
                () ->
                    new AdmissionValidationException(
                        AdmissionFailure.routingPolicyUnavailable(
                            "no active routing policy applies to the admission")));
    try {
      final var resolution =
          projection.resolve(
              new RoutingInstrument(
                  command.order().instrument().symbol().value(),
                  command.order().instrument().venueMic().value()));
      return AdmissionDeliveryRoute.assigned(
          resolution.routingPolicyId(), resolution.routingPartition());
    } catch (RoutingPolicyProjectionValidationException invalidAssignment) {
      throw new AdmissionValidationException(
          AdmissionFailure.routingInstrumentNotAssigned(
              "active routing policy has no assignment for the instrument"));
    }
  }
}
