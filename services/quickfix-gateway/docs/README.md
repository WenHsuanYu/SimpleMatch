# QuickFIX gateway specification

`quickfix-gateway` owns the external FIX session boundary. It normalizes FIX
messages, records inbound intent durably, and performs synchronous submission
to `risk-service`; it does not own risk decisions or matching order.

## Owned responsibilities

- Accept and manage FIX 4.4 sessions.
- Map supported inbound FIX order flow to internal commands.
- Append inbound traffic to the local write-ahead log before the first
  business-level acknowledgement.
- Return `PendingNew` only after `risk-service` has accepted durable admission.
- Map `matching.executions` back to outbound FIX responses for the owning
  session.

## Source of truth

The service's [runtime README](../README.md) is the canonical implementation
and operation guide. This page is the target-specification entry point: it
defines the gateway's ownership boundary without copying the shared event,
ordering, or reliability specifications.

Keep FIX-session and ingress-specific decisions with this service. Keep
cross-service protocols and architecture rules in `services/docs/`.
