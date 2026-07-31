# Persistence service specification

`persistence` owns durable read models, replay support, and audit-oriented projections. It is not an
authority for order admission, matching, or account state.

## Owned responsibilities

- Own the `persistence` schema and its projection lifecycle.
- Maintain order and execution read models once the corresponding consumer runtime is implemented.
- Record processed-event identities so projection consumers can be idempotent.

## Boundary and current scope

The repository contains the target projection schema, but not a Kafka consumer or projection writer
runtime. This document therefore describes the intended service boundary, not an implemented
event-consumption guarantee.

Event payloads, delivery semantics, and replay rules are shared concerns. They belong in the
cross-cutting contracts and platform documentation under
`services/docs/`, not in this service-local specification.

## Source of truth

This page is the target specification entry point for persistence-owned projection behavior. Add
service-specific consumer, projection, and audit decisions here when that runtime is introduced.
