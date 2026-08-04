# Target Architecture

These documents define the intended cross-cutting architecture. They describe the target state, not
a claim that every component is already implemented.

- [System boundaries](system-boundaries.md) — topology, ownership, service responsibilities, and the
  end-to-end data flow.
- [Ordering and latency](ordering-and-latency.md) — the deterministic matching boundary and the
  trade-offs around the hot path.
- [Eventing and CQRS](eventing-and-cqrs.md) — command, event, projection, and event-sourcing
  posture.
- [Routing Policy projections](routing-policy-projection.md) — Market Reference publication and
  Risk's strict local projection/readiness boundary.
- [Matching policy-aware ingress](matching-ingress.md) — the small native contract-verification
  seam; this is not the complete order book or Matching Engine.
- [Reliability and consistency](reliability-and-consistency.md) — durable acknowledgement,
  idempotency, retry, and compensation rules.
