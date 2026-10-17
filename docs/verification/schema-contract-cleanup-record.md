# Service schema contract cleanup record

This is the completed implementation and local-validation record for the first cleanup batch.
The acceptance and delivery sections preserve that batch's scope; the validation section records
its local results rather than current-head CI or deployment evidence. Later changes are recorded
in the [follow-up](schema-contract-followup-record.md) and
[hardening](schema-contract-hardening-record.md) records; the
[database implementation guide](../development/database-implementation-guide.md#current-schema-contract-cleanup) summarizes
the resulting schema contract.

This work follows the service SQL review and the owner's clarified assumptions: there is no
existing transaction-data compatibility requirement, Account admission v1 is retired, and each
symbol belongs to one of TWSE (XTAI) or TPEx (ROCO) in a trading day. The existing 1–12 uppercase
alphanumeric symbol contract remains in force. Matching/market-data runtime v1 and Gateway WAL v1
are independently versioned current contracts, not removal targets.

## Acceptance and ownership

| Slice | Owned paths | Acceptance |
| --- | --- | --- |
| Account | `services/account-service` | UUID-backed identities, mandatory venue/day, no legacy admission types/fallbacks, whole shares, exact bounded per-reservation fixed point, sufficient aggregate capacity, preserved reserve/fill/release and ledger invariants. |
| Query | `services/query-service` | Native UUID projections, complete reason text, legal statuses and positions, 32-byte hashes, unique symbol per reference day; mixed-source inbox IDs remain representable. |
| Market | `shared-java/market-reference-contract`, `tools/market-reference-builder`, `services/market-data-projection` | Artifact rejects duplicate symbol across venues; 12-character symbols and 17-character message keys work; incomplete last-trade pairs and malformed hashes fail. |
| Delivery/audit | Risk, Persistence, Gateway service directories | Legal admission/cancel state, current Matching audit tables only, exact durable FIX quantities, consistent delivery/quarantine timestamps and sufficiently wide log IDs. |
| Integration | Shared generation, scripts, root documentation | No obsolete common v1 generation or CI references to retired tables/scope fields; service-local Flyway ownership and runtime contract versions preserved. |

Each slice changes tests, Java/JDBC and SQL together. Workers do not edit another slice or root
configuration. Integration and complete-suite verification occur after focused tests. This avoids
concurrent ownership of Account public types and their external callers.

## Schema delivery decision

Use new versioned migrations through the existing Flyway convention. No legacy transaction values
are preserved by compatibility constructors, fallback dates or LEGACY venues. Keeping already
versioned DDL history is not a runtime v1 compatibility promise: the final schema is the contract.
Baseline squashing is deliberately unnecessary for these behavior fixes and would invalidate
existing Flyway histories and historical documentation links without improving the final schema.
No database or cluster reset is part of this work.

## Validation

- Exercise existing public domain/application/JDBC seams and SQL constraints with valid-row controls
  before individual invalid cases. Verify UUIDs, quantities, monetary bounds, nullable pairs,
  symbol/venue uniqueness, event replay and final delivery.
- Run affected module suites, the complete Java test suite, and `staticAnalysis` after integration.
- Run the registered Flyway migrate/validate tasks against an isolated PostgreSQL instance, repeat
  migration, and check owner histories plus existing query-index smoke assertions.
- Check migration layout, service registry, Markdown links and whitespace; review standards and
  specification separately against the pre-change commit `f39a4091c9ba`.
- Deployment certification is separate evidence. No existing cluster or production database is
  mutated merely to validate this schema change.

## Review decisions

Account summary retains its documented latest-lifecycle semantics; this task does not introduce
an account-wide aggregate projection. External FIX OrderID remains text (prefix plus ClOrdID),
and mixed UUID/SHA-256 inbox identifiers remain text. Textual enums with explicit CHECKs are
valid. No extra indexes are introduced without an actual query need, and the immutable PMD
ruleset is untouched.

## Implemented outcomes

| Review items | Result |
| --- | --- |
| Retired common v1 and Account fallbacks (1–5) | Removed old protobuf generation/inventory, introduced Account-owned reservation status and UUID-backed IDs, required current venue and business day. The source cutover guard rejects reintroduction. |
| Obsolete Persistence model (6) | V4 drops old orders/executions/inbox; registry, index smoke and migration tests use active Matching tables. |
| Symbol/venue identity and sizes (7–8) | Artifact validation rejects duplicate symbols across venues; Query reference adds per-day uniqueness; projection symbols12 and keys17 match the current contract. |
| SQL correctness and constraints (9–15) | Added explicit nullable-pair checks, UUID projections, complete reasons, legal state/value/hash/source-position and delivery/quarantine conditions. |
| Numeric and scope modeling (16–20) | Account shares use BIGINT; bounded exact scale4 reservation money and wider aggregate38,4 remain distinct; unused scope columns removed; durable FIX shares and log IDs use BIGINT. |
| Delivery and verification (21–24) | Added owner-scoped versioned migrations, positive-control/negative SQL tests, PostgreSQL CI probes and updated current documentation. Old phase evidence remains labeled historical. |

The baseline-squashing option was not needed: new versioned migrations create the intended final
schema while removing obsolete runtime code and tables. No compatibility adapters were added.
Index changes follow actual model removal; no speculative performance indexes or query-performance
claims are included.

The repository-local validation uses the complete Java test entrypoint plus Gateway certification
fixtures, static analysis, service-local Flyway info/migrate/validate, PostgreSQL positive/negative
probes, index smoke checks and source/Markdown checks. Live cluster deployment and production
certification remain separate work; this change does not claim either.

Timestamp consistency means state/presence and nonnegative values; new cross-worker timestamp-order
constraints are not introduced without a monotonic-clock guarantee. Gateway intent creation,
socket sending and administrative recovery may occur on different hosts or after a wall-clock
adjustment, so a send/recovery timestamp may be earlier without an invalid business transition.
Existing owner-specific chronology constraints are retained. Source offsets, identities and
lifecycle state provide ordering; timestamps are observations and are not rewritten to hide skew.

## Validation record

The integrated local Java run completed 666 service/shared/tool tests plus 3 Gateway certification
fixture tests with no failures; the separately run build-logic suite completed 1 test. Global
`staticAnalysis` passed after source fixes for imports, line length and exact amount-range checking.
The immutable PMD ruleset and warning policy were unchanged.

All 31 migrations across the six registered service owners passed the isolated PostgreSQL 18
Flyway CI runner: clean installation, repeat migration, validate, owner history isolation, current
index smoke checks and transactional positive/negative SQL probes. Probe fixtures rolled back.
The dedicated PostgreSQL instance was stopped and its run-owned data directory removed after
validation. This is local source evidence, not a remote CI result or deployment certification.
