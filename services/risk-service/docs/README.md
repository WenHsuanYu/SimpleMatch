# Risk service specification

`risk-service` is the authoritative owner of risk validation and durable order admission. A
successful admission response means that the service has persisted its decision; it does not mean
that matching has completed.

## Owned responsibilities

- Validate submit and cancel commands and return a stable acceptance or rejection decision.
- Persist accepted and rejected submission records in the `risk_service`
  schema.
- Write the corresponding outbox record in the same business transaction as a durable accepted
  decision.
- Preserve idempotent command handling through the command's stable business identity.

## Boundary

The service exposes the synchronous RPCs in
[`risk_service.proto`](../../../proto/risk_service.proto). It is the first durable business boundary
behind
`quickfix-gateway`.

After durable admission, the outbox supplies the integration boundary for ordered downstream work.
Event schemas, Kafka routing, and consumer guarantees are cross-cutting concerns and therefore
belong in `services/docs/`, rather than being duplicated here.

## Source of truth

This page is the target specification entry point for risk-owned behavior. Keep admission,
validation, and risk-owned persistence decisions here; keep shared architecture and contracts in
their cross-cutting canonical documents.

## Domain model

Risk Admission uses one `Admission` aggregate root per command identity. The root owns the
normalized decision facts, the FIX business-key idempotency identity, and the `PENDING` to
`ACCEPTED` or `REJECTED` lifecycle. Account reservations and matching orders are referenced through
their owning contexts rather than embedded in this root.

`AdmissionCommand` is composed from typed identity, order facts, FIX identity, and an optional
routing reference. Its canonical constructor cannot exchange command/order/account UUIDs or
sender/target/client-order strings. A durable submission result is likewise composed instead of
represented as one flat primitive record:

- `SubmissionReference` identifies the request, order, and normalized command type.
- `FixSubmissionIdentity` carries the FIX-facing business identity and trading day.
- `PersistedFixIdentity` carries storage-safe client-order identifiers and the surrogate decision.
- `SubmissionOutcome` is either accepted or contains one `SubmissionRejection`.
- `AdmissionFailure` represents a transport-independent reason that v2 admission cannot continue.

`SubmissionValidator` creates these values after normalization. JDBC and gRPC adapters translate
them to storage and protobuf fields; they do not define the business meaning. A business rejection
remains a durable domain outcome rather than an infrastructure exception or dead-letter event.

## Domain model

`AdmissionCommand` is composed from typed identity, order facts, FIX identity, and an optional
routing reference. Its canonical constructor cannot exchange command/order/account UUIDs or
sender/target/client-order strings. A durable submission result is likewise composed instead of
represented as one flat primitive record:

- `SubmissionReference` identifies the request, order, and normalized command type.
- `FixSubmissionIdentity` carries the FIX-facing business identity and trading day.
- `PersistedFixIdentity` carries storage-safe client-order identifiers and the surrogate decision.
- `SubmissionOutcome` is either accepted or contains one `SubmissionRejection`.
- `AdmissionFailure` represents a transport-independent reason that v2 admission cannot continue.

`SubmissionValidator` creates these values after normalization. JDBC and gRPC adapters translate
them to storage and protobuf fields; they do not define the business meaning. A business rejection
remains a durable domain outcome rather than an infrastructure exception or dead-letter event.
