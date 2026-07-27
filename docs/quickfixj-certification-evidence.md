# QuickFix/J Certification-Style Evidence

## Scope

This document records the current automated FIX simulator evidence for the Java `services/quickfix-gateway` baseline path.

It is not a counterparty certification report. It is a repo-local, repeatable QuickFIX/J integration proof that the Java gateway can:

- start a real FIX 4.4 acceptor
- create a session and complete logon
- accept `NewOrderSingle (35=D)` from a FIX initiator
- persist the inbound order to WAL before sending the baseline `ExecutionReport(PendingNew)`
- send the acknowledgement back on the same live FIX session
- complete logout and acceptor shutdown cleanly

## Evidence Source

- Test: `services/quickfix-gateway/src/test/java/com/simplematch/quickfixgateway/fix/QuickFixCertificationEvidenceTest.java`
- Style: automated QuickFIX/J socket initiator against the real Java acceptor lifecycle
- Date first recorded: 2026-03-27

## Scenario

The test dynamically creates:

- a temporary acceptor QuickFIX config
- a temporary initiator QuickFIX config
- temporary FIX store and log directories
- a temporary gateway WAL path

It then:

1. starts `QuickFixAcceptorLifecycle`
2. starts a QuickFIX/J `SocketInitiator` acting as the simulator
3. loads the repository-local dictionary at `../config/quickfix/fix-spec/FIX44.xml`
4. waits for FIX logon
5. sends `NewOrderSingle (35=D)` with `ClOrdID=C1`
6. waits for the `ExecutionReport(PendingNew)` reply
7. reads the gateway WAL file
8. stops initiator and acceptor
9. asserts lifecycle logs and baseline message behavior

## Verified Outcomes

The automated simulator currently verifies all of the following in one run:

- session creation is logged by `QuickFixApplicationAdapter`
- FIX logon is completed and logged
- inbound application traffic reaches `fromApp(...)`
- baseline `35=D -> WAL -> PendingNew` path executes end-to-end
- the simulator does not depend on the old vendored QuickFIX dictionary path from the removed C++ baseline
- outbound acknowledgement fields include:
  - `35=8`
  - `37=O-C1`
  - `17=E-<recordId>`
  - `150=A`
  - `39=A`
  - `54=1`
  - `151=10`
  - `14=0`
  - `6=0`
  - `11=C1`
  - `55=AAPL`
- WAL contains the inbound `35=D` order after the live FIX interaction
- FIX logout is logged
- acceptor shutdown is logged cleanly

## Rerun Command

```bash
./gradlew :services:quickfix-gateway:certificationTest
```

Equivalent direct test filter:

```bash
./gradlew :services:quickfix-gateway:test --tests com.simplematch.quickfixgateway.fix.QuickFixCertificationEvidenceTest
```

## Interpretation

This evidence closes the previous gap where the Java gateway had unit-level parity checks but no automated FIX-simulator proof for session lifecycle and the supported baseline order flow.

Repository baseline note:

- the current repo does not contain an equivalent C++ FIX simulator artifact
- the current repo does not contain a recorded C++ broker or venue certification report
- the current repo does contain C++ unit-level evidence for config precedence and `ExecutionReport(PendingNew)` field mapping

The remaining certification gap is external, not internal:

- no broker or venue-specific certification script is recorded yet
- no counterparty interoperability evidence is recorded yet

Those are future operational validation steps beyond this repo-local certification-style check.