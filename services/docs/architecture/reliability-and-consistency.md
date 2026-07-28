# Reliability and Consistency

This is the canonical target specification for reliability, consistency, and failure handling in SimpleMatch's target
architecture.

## Delivery model

The system assumes at-least-once delivery. Duplicate messages may occur during retry, failover, or replay, so the
promise is not "no duplicate message"; it is
"a duplicate does not produce a duplicate business effect." Every consumer therefore uses one or more of:

- a processed-event or inbox record keyed by `event_id`;
- a unique key such as `(order_id, exec_id)`;
- a state-machine transition check; or
- an idempotent request identity for synchronous writes.

## Durable admission and publication

The first successful FIX acknowledgement means `risk-service` has committed the submission and its outbox record in one
local PostgreSQL transaction. The gateway must return `Rejected` for an immediate risk failure rather than first
acknowledging success.

The intended publication pattern is:

1. A service commits its business record and outbox record atomically.
2. Debezium observes the committed outbox change and publishes the integration event.
3. Consumers apply their own idempotent local transaction.

For matching results, a local WAL or journal provides the first post-match durability anchor. A loader can write the
results and outbox record in a later local transaction, then allow CDC to publish them. This keeps downstream I/O out of
the matching loop while preserving a recoverable path.

## Synchronous dependency policy

Every outbound gRPC call has a deadline. Read operations may use a bounded, jittered retry for transient transport
failures. Write operations do not retry automatically unless the request carries a stable idempotency identity and the
receiver enforces it with a unique constraint or equivalent state rule.

Circuit breakers fail fast after repeated dependency failures; bulkheads keep one unhealthy dependency from exhausting
all resources. Risk and reservation decisions fail closed. Read-only market-data and query paths may intentionally
degrade to a stale cache when their contract permits it.

## State progression and compensation

An accepted order progresses through clear states such as `PENDING`, optional
`RISK_CHECKING`, `MATCHING`, and terminal `FILLED`, `CANCELLED`, `REJECTED`, or
`EXPIRED`. The state makes the durable admission boundary visible without claiming that matching has already completed.

Cross-service work uses choreography rather than a distributed transaction. Each service reacts to an event and emits
its next event. A cancellation stops remaining quantity but does not undo a completed execution. If a completed
execution needs correction, that is an explicit compensating business event, not a rollback of history. Failed
downstream projection writes recover through retry and replay, protected by the idempotency rules above.

Kafka durability settings, event identities, and retryable protocol errors are contract details. This document defines
the system-level consistency guarantees they must support.
