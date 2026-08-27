# QuickFix/J Certification-Style Evidence

## Scope

This document records the current automated FIX simulator evidence for the Java
`services/quickfix-gateway` baseline path.

It is not a counterparty certification report. It is a repo-local, repeatable QuickFIX/J integration
proof that the Java gateway can:

- start a real FIX 4.4 acceptor;
- create a session and complete logon;
- accept `NewOrderSingle (35=D)` from a FIX initiator;
- accept `OrderCancelRequest (35=F)` through the public gateway boundary;
- validate canonical UUID `Account(1)` at ingress;
- persist the inbound command to WAL before Risk submission;
- keep recovery state in the sidecar journal and reconcile unresolved Risk outcomes;
- send the supported acknowledgement back on the same live FIX session; and
- complete logout and acceptor shutdown cleanly.

The architectural meaning of these states is defined by
[Consistency, Recovery, Identity, and Error Boundaries](../services/docs/platform/consistency-recovery-identity-and-errors.md).

## Evidence Source

- Test:
  `services/quickfix-gateway/src/test/java/com/simplematch/quickfixgateway/fix/QuickFixCertificationEvidenceTest.java`
- Style: automated QuickFIX/J socket initiator against the real Java acceptor lifecycle
- Date first recorded: 2026-03-27

## Scenario

The test dynamically creates:

- a temporary acceptor QuickFIX config;
- a temporary initiator QuickFIX config;
- temporary FIX store and log directories; and
- temporary gateway WAL/recovery state.

It then exercises the supported baseline, including live new-order ingress, durable command state,
Risk submission evidence, outbound FIX acknowledgement, lifecycle cleanup, duplicate behavior,
cancellation, and startup recovery scenarios.

The certification test intentionally verifies observable invariants rather than thread timing. Where
FIX delivery and an internal asynchronous side effect are separate events, the test waits for the
specific event it asserts instead of assuming one event proves the other completed.

## Verified Outcomes

The automated evidence covers:

- session creation, FIX logon, inbound application traffic, logout, and clean acceptor shutdown;
- `35=D` reaching the durable WAL path before the supported business acknowledgement;
- canonical Account UUID validation at the FIX boundary;
- stable external `OrderID(37)=O-<ClOrdID>` behavior;
- duplicate and cancel behavior through the public Gateway boundary;
- state-aware recovery rather than blind WAL resubmission;
- terminal recovery state skipping;
- reconciliation of unresolved commands before retry decisions;
- preservation of the original `command_id` when recovery permits resubmission; and
- the repository-local FIX dictionary rather than the removed C++ dictionary path.

The live baseline acknowledgement continues to verify the expected FIX 4.4 field shape, including
`ExecutionReport`, client `ClOrdID`, symbol, side, quantities, and traceable execution identity.
Exact assertions remain authoritative in `QuickFixCertificationEvidenceTest` so this document does
not duplicate every field literal and drift from the executable evidence.

## Retained-session retransmission evidence

The production-like failure certification also exercises a retained external FIX session after the
client has been disconnected while a durable delivery intent is pending. The first lifecycle
`ExecutionReport` is verified through the normal QuickFIX/J `Application.fromApp` callback. This
proves that the ordinary application-delivery path remains functional after recovery.

An explicit `ResendRequest (35=2)` for an already processed server sequence is verified at the raw
incoming FIX log seam instead of through a second `Application.fromApp` callback. QuickFIX/J checks
sequence numbers before application dispatch. A valid retransmission for a sequence lower than the
initiator's current expected target sequence carries `PossDupFlag(43)=Y`, but the engine deliberately
discards that already-processed message after duplicate validation rather than delivering it to the
application again.

The wire-level observation therefore verifies the protocol property that matters without weakening
QuickFIX/J sequence semantics. The retransmitted `ExecutionReport` must retain its original
`MsgSeqNum(34)` and `ExecID(17)`, set `PossDupFlag(43)=Y`, and provide `OrigSendingTime(122)`. The same
invariants are checked again after a QuickFIX Gateway restart, which exercises the JDBC-backed
QuickFIX/J message store rather than an application-level reconstruction of the report.

The live QuickFIX Gradle tasks are state-untracked because they interact with external endpoints and
write evidence outside Gradle-managed outputs. They must execute on every certification invocation;
a previous local test result is not reusable evidence for a new deployed-system run.

## Rerun Command

```bash
./gradlew :services:quickfix-gateway:certificationTest
```

The certification class is intentionally owned by the dedicated `certificationTest` task rather than
being executed a second time by the ordinary module `test` task.

## Interpretation

This evidence proves the repo-local Java Gateway baseline, including the current recovery and
identity boundaries. It does not claim external venue or broker certification.

The remaining certification gap is external:

- no broker or venue-specific certification script is recorded yet; and
- no counterparty interoperability evidence is recorded yet.

Those are future operational validation steps beyond this repo-local certification-style check.
