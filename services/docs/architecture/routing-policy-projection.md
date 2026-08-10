# Daily routing artifact loading

Routing Policy is no longer a runtime projection published by Market Reference. The accepted
Phase 1 authority is one reviewed, immutable `market_reference.json` for each Asia/Taipei trading
day. An offline builder obtains official TWSE/TPEx facts, normalizes the eligible XTAI and ROCO
regular-board common-stock universe, calculates stable assignments across 15 partitions, and adds
the official reference and limit prices before finalization.

The file contains four sections: `metadata`, `marketRules`, `marketSnapshot`, and `routingPolicy`.
Its identity is the trading day plus SHA-256 of the exact canonical UTF-8 bytes. The checksum is
delivered separately so a corrupt file cannot certify itself. Routing uses a fixed algorithm
version, an initial `(venueMic, symbol)` ordering, and least-loaded assignment with the lowest
partition ID as the tie-breaker. A partition contains no more than 150 instruments.

## Runtime boundary

Risk and every Matching pod mount and load the same final artifact at startup. They validate:

- trading day, schema, routing-algorithm version, and external content checksum;
- the complete, normalized, unique eligible instrument set;
- exactly 15 partition IDs and no assignment outside `0..14`;
- no duplicate or omitted assignment and no partition above 150 instruments; and
- supported market rules, reference prices, and price limits.

There is no hot reload. A missing, stale, malformed, incomplete, oversized-for-its-delivery-mode,
or mismatched artifact keeps the component NotReady. Risk resolves each accepted command to the
artifact-assigned `matching.commands` partition and persists that delivery route before remote work.
Matching ordinal N verifies that the command belongs to partition N before processing it.

## Delivery boundary

The normal delivery is an immutable ConfigMap while the file remains at or below 900 KiB. If it is
larger, the builder packages the same bytes in a digest-pinned OCI data image. Both modes expose the
same application mount path and checksum contract. Kubernetes transports the reviewed artifact but
does not decide whether its market contents are valid.

Market Reference therefore owns offline source acquisition and artifact construction, not a
runtime API, PostgreSQL schema, Kafka topic, outbox, activation service, or Risk projection table.
Current implementation evidence and removal work are tracked in the
[remaining-work inventory](../../../docs/routing-policy-remaining-work.md).

The executable builder and delivery contracts are documented separately:

- [Official-source contract](../../../docs/market-reference-official-source-contract.md)
- [Canonical artifact contract](../../../docs/market-reference-artifact-contract.md)
- [Daily approval workflow](../../../docs/market-reference-approval-workflow.md)
