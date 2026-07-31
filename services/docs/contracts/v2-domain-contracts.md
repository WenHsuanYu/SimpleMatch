# V2 Domain Contracts

This page records the additive v2 Protobuf contracts that establish the transition vocabulary. It
does not claim that live services have switched from v1; routing remains a later phase.

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
  `ShareQuantity` uses signed 64-bit whole shares.
- The only v2 currency is `TWD`; phase-one venues are `XTAI` and `ROCO`.
- Absolute times are UTC milliseconds. `TradingDay` is an ISO date interpreted in `Asia/Taipei`, and
  session state is explicit.
- ROD, IOC, and FOK are all represented by the v2 time-in-force enum. Their execution rules are
  implemented in later matching phases.

## Compatibility

The checked-in v2 descriptor inventory is the field-number compatibility baseline. Published message
fields are append-only: a field number must never be reused or assigned a different meaning. A
change that needs incompatible semantics creates a new message or versioned contract rather than
modifying an existing field.

`V1OrderCommandAdapter` is an explicit ingress seam for representable v1 commands. It validates
values while converting and round-trips the v1 command without changing the existing services' v1
routing. The deployment boundary supplies the legacy venue because v1 carries no MIC. This adapter
remains until the later v2 admission migration is complete.
