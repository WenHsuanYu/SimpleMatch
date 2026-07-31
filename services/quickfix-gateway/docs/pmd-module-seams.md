# FIX Gateway PMD module seams

The FIX Gateway does not use PMD design suppressions. Its complexity is held behind the following
cohesive modules while its external protocol behavior remains stable.

- `InboundFixMessageHandler` is the single public ingress seam. `NewOrderFixMessageHandler` and
  `CancelOrderFixMessageHandler` own their respective durable admission paths.
- `FixMessageMapper` is the stable outbound rendering seam. Its execution-report and cancel-reject
  implementations share `FixWireValues` for FIX 4.4 normalization.
- `OrderSessionState` holds immutable session correlation, while `OrderSessionLifecycle` owns the
  mutable order status and outstanding cancel correlation.
- `WalOrderCommandMapper` converts durable WAL records to the v1 compatibility command and
  centralizes null normalization.
- The gateway configuration is separated into runtime, integration, and lifecycle wiring modules.

These seams preserve the existing v1 command payload, FIX field values, session ownership, and
compatibility publishing semantics. Golden-message tests and QuickFIX certification remain the
behavioral contract for future refactoring.
