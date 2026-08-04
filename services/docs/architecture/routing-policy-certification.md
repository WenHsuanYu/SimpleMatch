# Routing Policy migration certification

## Scope

This document records the final certification for the Market Reference Routing Policy migration
tracked by [#77](https://github.com/WenHsuanYu/SimpleMatch/issues/77) and implementation issues
[#78](https://github.com/WenHsuanYu/SimpleMatch/issues/78) through
[#86](https://github.com/WenHsuanYu/SimpleMatch/issues/86). It certifies the policy-aware path; it
does not claim that the complete C++ Matching Engine, runtime market-data streamer, or query
service already exists.

Market Reference is the sole routing-policy authority. It publishes a complete immutable policy;
Risk and Matching persist local projections and activate them atomically. Admission persists the
resolved policy identity and explicit Kafka partition before remote work, while Matching verifies
the same policy assignment before processing the order.

`routingPolicyId` identifies the published policy artifact. `routingSnapshotId` remains opaque
ingress metadata and is never used as a substitute for policy identity. A legacy pending row may
have a null policy identity, but its persisted partition is preserved without recomputation.

## Certification evidence

- Shared v1/v2 contract generation, fixture compatibility, Market Reference, and Risk checks pass:
  `GRADLE_USER_HOME=/tmp/simplematch-routing-gradle ./gradlew --no-daemon :shared-java:simplematch-contracts:check :services:marketdata-publisher:check :services:risk-service:check`.
- The native Matching ingress consumes the same Java-generated policy and order fixtures; CTest
  passes all four deterministic tests in `/tmp/simplematch-native-84`.
- `bash scripts/run-flyway-ci-checks.sh` passes the account-service, persistence, and risk-service
  empty-schema, migrate-twice, and PostgreSQL assertion checks.
- The blocking Java gate passes with
  `GRADLE_USER_HOME=/tmp/simplematch-routing-gradle ./gradlew --no-daemon -q staticAnalysis --continue`.
  The command emits only existing JVM/Lombok deprecation warnings.
- The production inventory contains no Risk-local routing JSON loader, hash partition fallback,
  default partition constant, or obsolete routing configuration. The only remaining textual match
  is the intentional protobuf type name in a decoder Javadoc.
- The source inventory keeps `routingPolicyId` and `routingSnapshotId` separate in the v2 ingress,
  admission journal, outbox, and projection paths.
- Markdown navigation checks, `git diff --check`, graph refresh, and the bad-smell review are part
  of this certification commit and are recorded in the issue comment.

## Operational boundary and remaining risks

- Market Reference must publish and consumers must preload a complete policy before the trading-day
  readiness gate opens. Missing, expired, incomplete, or mismatched policy state fails closed.
- Taiwan cash-equity routing is one continuous session. Intraday publication may add instruments,
  but it cannot reassign or omit an instrument already routed earlier that trading day.
- The native implementation is currently the policy-aware ingress seam, not a full order book or
  matching engine. That remains a later architecture phase.
- The graph refresh still reports zero-node warnings for unrelated non-code snapshot/configuration
  files. They do not affect the policy contract or runtime route selection.
