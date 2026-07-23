# Target Contracts

This area owns the intended cross-service protocol and compatibility contracts.

- [Kafka event contracts](kafka-events.md) — topics, ordering, delivery, and
  event identity.
- [gRPC API contracts](grpc-apis.md) — synchronous admission, deadlines,
  retries, and idempotent writes.
- [FIX gateway contract](fix-gateway.md) — the external FIX 4.4 boundary and
  execution-report mapping.

Service-local implementation and certification material remains with its
owning service. New cross-service links should target a canonical document in
this area rather than reproduce its rules.
