# Risk service specification

`risk-service` is the authoritative owner of risk validation and durable order
admission. A successful admission response means that the service has persisted
its decision; it does not mean that matching has completed.

## Owned responsibilities

- Validate submit and cancel commands and return a stable acceptance or
  rejection decision.
- Persist accepted and rejected submission records in the `risk_service`
  schema.
- Write the corresponding outbox record in the same business transaction as a
  durable accepted decision.
- Preserve idempotent command handling through the command's stable business
  identity.

## Boundary

The service exposes the synchronous RPCs in
[`risk_service.proto`](../../../proto/risk_service.proto). It is the first
durable business boundary behind `quickfix-gateway`.

After durable admission, the outbox supplies the integration boundary for
ordered downstream work. Event schemas, Kafka routing, and consumer guarantees
are cross-cutting concerns and therefore belong in `services/docs/`, rather
than being duplicated here.

## Source of truth

This page is the target specification entry point for risk-owned behavior.
Keep admission, validation, and risk-owned persistence decisions here; keep
shared architecture and contracts in their cross-cutting canonical documents.
