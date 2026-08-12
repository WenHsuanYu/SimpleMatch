# V2 Domain Contracts

This page records the additive v2 Protobuf contracts that define the current typed admission
vocabulary. QuickFIX Gateway and Risk now use the v2 order-admission contracts on the production
synchronous path; other domains may still have independently versioned v1 event contracts while
their own migrations remain incomplete.

## Contract shape

The v2 files are `common_v2.proto`, `orders_v2.proto`, `account_v2.proto`, and
`matching_v2.proto`. Every v2 command or event carries `EventMetadata` with a literal
`schema_version` of `v2`, UUID event/correlation identifiers, a UTC millisecond timestamp, and a
source service. Causation is optional but, when present, is also a UUID.

`NewOrderCommand` and `CancelOrderCommand` are commands. Admission, account, reservation, lifecycle,
and execution messages are facts emitted after an owning service has made a durable state decision.
A rejection is a domain fact, not a transport failure or dead-letter record.

## Domain values

- Internal event, command, order, account, reservation, execution, and snapshot identities are
  UUID-backed. FIX sender/target, trading day, and client order identity remain a separate business
  key.
- `TwdPrice` and `TwdNotional` use signed 64-bit units of `0.0001 TWD`.
- Account reservation uses the typed `AccountReservationService.Reserve` RPC. Its command carries
  UUID-backed request/order/account identity, a venue-qualified instrument, side, whole-share
  quantity, fixed-point limit price, and fixed-point notional. The response is an authoritative
  `AccountLifecycleEvent` projection; the durable event remains written by Account Authority in
  the same transaction as the reservation. Account persists the venue MIC with the reservation, so
  a repeated command with a different venue is a stable request conflict rather than an equivalent
  replay.
  `ShareQuantity` uses signed 64-bit whole shares.
- The only v2 currency is `TWD`; phase-one venues are `XTAI` and `ROCO`.
- Absolute times are UTC milliseconds. `TradingDay` is an ISO date interpreted in `Asia/Taipei`, and
  session state is explicit.
- ROD, IOC, and FOK are all represented by the v2 time-in-force enum.

## Account reservation saga boundary

Risk owns the durable Admission intent, not the Account reservation transaction. It commits
`PENDING` in one local transaction, calls `AccountReservationService.Reserve` with the typed v2
command outside that transaction, and commits the terminal Risk journal/outbox state in a second
local transaction. Account owns its own `@Transactional` reservation mutation and lifecycle outbox.

If Account succeeds but Risk's terminal transaction fails, the pending row remains recoverable.
Pending recovery sends the original command identity again; Account's request-identity lock and
equivalent-fact check return the existing reservation outcome, so the recovery path cannot reserve
the account twice. The repository-local proof is
`AccountReservationSagaRecoveryIntegrationTest`, together with the Account v2 gRPC and source
cutover tests.

The retained Account v1 server is a compatibility surface owned by #119 until the later cleanup.
No in-scope Risk production caller may construct that client after the v2 cutover. External
production certification, live staging/production configuration, and external Kafka/Debezium
certification are promotion-template concerns, not prerequisites for this repository-local scope.

## QuickFIX to Risk production boundary

QuickFIX Gateway does not construct a v1 `OrderCommand` on the production admission path. It keeps a
Gateway-owned durable `WalRecord`, then maps that record directly to a validated v2
`NewOrderCommand` or `CancelOrderCommand` before calling Risk v2. Live submission and startup
resubmission use the same mapper so command identity, typed values, and internal order identity
cannot drift between paths.

The Gateway WAL has its own local persistence schema. A WAL record whose JSON `schemaVersion` is
`v1` therefore does not imply a v1 Risk service contract; it identifies the stable WAL encoding.

## Compatibility

The checked-in v2 descriptor inventory is the field-number compatibility baseline. Published message
fields are append-only: a field number must never be reused or assigned a different meaning. A
change that needs incompatible semantics creates a new message or versioned contract rather than
modifying an existing field.

`V1OrderCommandAdapter` remains a shared compatibility utility for explicitly representable legacy
commands and contract tests. It is not part of QuickFIX live submission, WAL recovery, or the
production Risk v2 admission path. New production code must not route through it merely because a
legacy message type remains available in the repository.

Its v1-to-v2 and v2-to-v1 field-family mapping collaborators are package-private implementation
details. Removing the utility itself is a separate shared-contract cleanup once no remaining
compatibility or inventory use requires it.
