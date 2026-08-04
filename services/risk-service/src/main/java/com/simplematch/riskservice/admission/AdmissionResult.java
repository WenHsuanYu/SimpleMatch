package com.simplematch.riskservice.admission;

import java.util.Objects;
import java.util.UUID;

/**
 * Projection of an admission identity, decision, routing provenance, and delivery route.
 *
 * <p>This projection intentionally excludes journal revision and full order facts.
 *
 * @param identity command, order, and account identity
 * @param decision pending or terminal admission decision
 * @param routing opaque ingress routing snapshot reference
 * @param route persisted delivery route
 */
public record AdmissionResult(
    AdmissionIdentity identity,
    AdmissionDecision decision,
    AdmissionRoutingReference routing,
    AdmissionDeliveryRoute route) {
  /** Requires the semantic values needed by an application or transport response. */
  public AdmissionResult {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(routing, "routing");
    Objects.requireNonNull(route, "route");
  }

  /** Projects a journal entry without exposing its revision or full order facts. */
  public static AdmissionResult from(AdmissionJournalEntry entry) {
    Objects.requireNonNull(entry, "entry");
    return new AdmissionResult(
        entry.command().identity(),
        entry.lifecycle().decision(),
        entry.command().routing(),
        entry.route());
  }

  /** Returns the command identifier carried by this response projection. */
  public UUID commandId() {
    return identity.commandId().value();
  }

  /** Returns the order identifier carried by this response projection. */
  public UUID orderId() {
    return identity.orderId().value();
  }

  /** Returns the account identifier carried by this response projection. */
  public UUID accountId() {
    return identity.accountId().value();
  }

  /** Returns the storage-compatible decision state. */
  public AdmissionState state() {
    return decision.state();
  }

  /** Returns the accepted-new reservation identifier, or {@code null} otherwise. */
  public UUID reservationId() {
    return decision instanceof AdmissionDecision.AcceptedNew accepted
        ? accepted.reservationId()
        : null;
  }

  /** Returns the opaque ingress routing snapshot as the existing response string. */
  public String routingSnapshotId() {
    final UUID snapshotId = routing.snapshotId().value();
    return snapshotId == null ? "" : snapshotId.toString();
  }

  /** Returns the authoritative policy identity selected for this delivery route, when present. */
  public UUID routingPolicyId() {
    return route.routingPolicyId();
  }

  /** Returns the persisted delivery partition, when assigned. */
  public Integer routingPartition() {
    return route.routingPartition();
  }

  /** Returns the rejection code, or an empty value for non-rejected decisions. */
  public String reasonCode() {
    return decision instanceof AdmissionDecision.Rejected rejected
        ? rejected.failure().reasonCode().value()
        : "";
  }

  /** Returns rejection detail, or an empty value for non-rejected decisions. */
  public String reasonDetail() {
    return decision instanceof AdmissionDecision.Rejected rejected
        ? rejected.failure().detail().value()
        : "";
  }
}
