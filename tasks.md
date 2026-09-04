# SimpleMatch Task Index and Historical Checklist

This file is navigation and implementation history. It is not a live task tracker.

## Task authority

Executable work, assignment, blocking, and completion live in
[GitHub Issues](https://github.com/WenHsuanYu/SimpleMatch/issues). The parent architecture program is
[#10](https://github.com/WenHsuanYu/SimpleMatch/issues/10); its native sub-issues and dependency
edges are the authoritative current task graph.

Use the repository documents for these distinct questions:

| Question | Authority |
| --- | --- |
| What work is open, assigned, blocked, or complete? | GitHub Issues |
| What exactly is included in the Phase 1 Trading Release? | [System boundaries](services/docs/architecture/system-boundaries.md#phase-1-trading-release-boundary) |
| What Phase 1 capability exists or remains, and what evidence supports that status? | [Phase 1 Trading Release remaining work](docs/routing-policy-remaining-work.md) |
| What architecture was accepted? | [Accepted Phase 1 ADRs](docs/adr/0008-offline-market-reference-artifact.md), [domain language](CONTEXT.md), and [target specifications](services/docs/README.md) |
| What is the historical phase, transaction, rollback, and final-gate plan? | [Archived Taiwan event-driven refactor plan](docs/archive/taiwan-event-driven-refactor-plan.md) |
| What was implemented historically? | This file and Git history |

An accepted design or checked historical milestone is not evidence that a current GitHub Issue is
complete. Close an issue only with its required implementation and verification evidence.

## Current executable frontier

### Market Reference Artifact

- [#121 Build offline TWSE/TPEx Market Reference source tool](https://github.com/WenHsuanYu/SimpleMatch/issues/121)
- [#122 Define canonical Market Reference Artifact](https://github.com/WenHsuanYu/SimpleMatch/issues/122)
- [#123 Implement deterministic stable routing allocation](https://github.com/WenHsuanYu/SimpleMatch/issues/123)
- [#124 Implement daily Market Reference approval workflow](https://github.com/WenHsuanYu/SimpleMatch/issues/124)

### Matching command and event path

- [#125 Provision Matching Kafka durability profile](https://github.com/WenHsuanYu/SimpleMatch/issues/125)
- [#126 Cut Risk over to daily artifact and matching.commands](https://github.com/WenHsuanYu/SimpleMatch/issues/126)
- [#127 Build native LMAX-style Matching runtime](https://github.com/WenHsuanYu/SimpleMatch/issues/127)
- [#128 Implement Matching Kafka replay and trading-day barriers](https://github.com/WenHsuanYu/SimpleMatch/issues/128)
- [#129 Define and publish matching.events](https://github.com/WenHsuanYu/SimpleMatch/issues/129)

### Consumers and projections

- [#130 Persist Matching trades and order-fill legs](https://github.com/WenHsuanYu/SimpleMatch/issues/130)
- [#131 Migrate Account Authority to matching.events](https://github.com/WenHsuanYu/SimpleMatch/issues/131)
- [#132 Implement durable QuickFIX Matching Event delivery](https://github.com/WenHsuanYu/SimpleMatch/issues/132)
- [#133 Build runtime Matching Event market-data projection](https://github.com/WenHsuanYu/SimpleMatch/issues/133)

### Required read paths

- [#137 Build required Phase 1 query service and read models](https://github.com/WenHsuanYu/SimpleMatch/issues/137)

### Deployment, operations, and certification

- [#134 Deploy and fence 15-pod Matching StatefulSet](https://github.com/WenHsuanYu/SimpleMatch/issues/134)
- [#135 Implement Gateway trading admission operations](https://github.com/WenHsuanYu/SimpleMatch/issues/135)
- [#138 Harden Phase 1 cross-service deployment and security](https://github.com/WenHsuanYu/SimpleMatch/issues/138)
- [#136 Certify Matching capacity, latency, and recovery](https://github.com/WenHsuanYu/SimpleMatch/issues/136)

### Cleanup and retained delivery work

- [#119 Retire compatibility seams and superseded runtime paths](https://github.com/WenHsuanYu/SimpleMatch/issues/119)
  owns dependency-gated cleanup.
- [#120 Remove dead QuickFIX command publication](https://github.com/WenHsuanYu/SimpleMatch/issues/120)
  is a child of #119 and is implemented locally but remains open until delivered and verified.
- [#92 Verify retained Risk and Account binary CDC publication](https://github.com/WenHsuanYu/SimpleMatch/issues/92)
  remains valid and is re-certified after #126.
- [#139 Migrate Account reservation RPC to final v2 contract](https://github.com/WenHsuanYu/SimpleMatch/issues/139)
  must complete before #119 removes the production Account v1 transport.

GitHub's native `blocked by` edges define the executable order. In summary: #121→#122→#123→
#124 builds the artifact path; #125 and #126 establish command delivery; #127–#129 establish
Matching; #130–#133 consume its events; #137 builds required reads; #134 and #135 establish Matching
deployment/admission; #139 replaces Account v1; #138 integrates the final cross-service deployment
and security baseline; #136 certifies Matching; and #119 deletes old paths only after its
replacements pass.

## Historical implementation record

The detailed historical checklists were intentionally removed from this file after the work was
filed into GitHub Issues. Git history preserves their exact text. The durable summary is:

- Repository baseline, build/dependency policy, Spring configuration, typed v2 contracts, and clean
  typed V1 Flyway foundations were completed in Phases 0–4.
- Pure Market Reference snapshot, calendar, tick, eligibility, routing, and codec foundations were
  implemented, but their runtime Spring/PostgreSQL/outbox/Kafka publication model was superseded by
  ADR 0008.
- Account reservation authority and durable Risk Admission were implemented with local transaction,
  idempotency, outbox, recovery, and backpressure foundations.
- QuickFIX ingress, WAL recovery, Risk submission, FIX mapping, session, and admission-gate
  foundations exist; durable critical Matching Event delivery and one-Gateway operations remain
  open.
- Risk and Account outbox/CDC, ordered retry, quarantine, and metrics provide reusable Phase 9
  foundations. Runtime Market Reference CDC and legacy Routing Policy consumers are removal work.
- Native Matching currently contains CMake plus routing/quarantine ingress state machines, not a
  complete Kafka/ring/order-book/publisher runtime.
- Persistence currently has its application/Flyway baseline, not the final trades/fills consumer.
- Query service and Redis order/execution/account read models are not implemented.
- Kubernetes has partial QuickFIX/Risk scaffolding, not the fixed fenced 15-pod Matching fleet or
  cross-service production security baseline.
- The local commits associated with #120 remove dead QuickFIX compatibility publication, but remote
  issue/delivery state remains authoritative.

Closed [#87](https://github.com/WenHsuanYu/SimpleMatch/issues/87) and #93–#99 retain the former Phase
8/9 design history and point to their accepted replacement issues. They are not active tasks.

## Canonical specifications

- [System boundaries](services/docs/architecture/system-boundaries.md)
- [Daily routing artifact loading](services/docs/architecture/routing-policy-projection.md)
- [Matching ingress and recovery](services/docs/architecture/matching-ingress.md)
- [Ordering and latency](services/docs/architecture/ordering-and-latency.md)
- [Kafka event contracts](services/docs/contracts/kafka-events.md)
- [FIX Gateway contract](services/docs/contracts/fix-gateway.md)
- [ADR 0008: Offline Market Reference Artifact](docs/adr/0008-offline-market-reference-artifact.md)
- [ADR 0009: Kafka-journaled native Matching](docs/adr/0009-kafka-journaled-lmax-matching.md)
- [ADR 0010: Fixed Matching fleet and Gateway admission](docs/adr/0010-fixed-matching-fleet-and-admission.md)

## Maintenance rule

Do not add a new live checkbox to this file. File or update a GitHub Issue, add native parent and
dependency relationships, then update the remaining-work inventory only when capability status or
evidence changes. Update the refactor plan only when phase, transaction, rollback, or final-gate
semantics change.
