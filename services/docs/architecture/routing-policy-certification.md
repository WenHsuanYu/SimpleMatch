# Historical Routing Policy Migration Certification

> Superseded target. This page records the former runtime Market Reference publication and
> projection design for migration history; it is not a current deployment or certification
> command.

## Replacement boundary

The accepted target is the offline daily artifact produced by the
[`config/market-reference/README.md`](../../../config/market-reference/README.md) workflow and
implemented in `tools/market-reference-builder`. Its pure
normalization, Taiwan calendar, eligibility, tick-table, deterministic assignment, codec, and
checksum behavior is tested before an immutable artifact is approved. Risk and Matching load that
same artifact at startup and fail closed on missing, stale, incomplete, or mismatched content.

The former runtime publisher, routing-policy Kafka projection, native policy-ingress lifecycle,
owner schemas, outboxes, connectors, and Market Reference topics were removed by #119. Their
historical implementation and evidence remain available through Git history only. Current
certification uses the final `matching.commands` and `matching.events` paths plus the artifact
identity carried by the Open Barrier and command envelope.

## Historical evidence boundary

The old migration tests established transaction, duplicate, checksum, and deterministic-assignment
invariants. They must not be rerun as a current service gate because the service no longer exists.
Use the offline builder tests, active Risk/Matching contract tests, Flyway checks for the retained
service schemas, and the repository-owned local production-like certification runner instead.
