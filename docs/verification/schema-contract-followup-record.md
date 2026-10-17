# Schema contract follow-up record

This completed record contains two successive batches with separate local validation results.
The 2026-10-17 [canonical reason storage revision](#canonical-reason-storage-revision) is the final
reason-storage decision in this document; the initial Unicode SQL design below is historical.
Read each test/migration count with its own batch and source boundary. Later optional items were
delivered in the separate [hardening record](schema-contract-hardening-record.md).

This bounded follow-up starts at `e78d7e0`, after the first schema cleanup. The owner approved the
four recommended follow-ups from the residual assessment; optional reference-price constraints,
FIX session-ID capacity changes, mixed event-ID format changes and unused-column removal are
outside this batch. Existing transaction-data compatibility is not required.

## Initial acceptance

1. The shared final Matching Event validator requires XTAI/ROCO and 1–12 uppercase alphanumeric
   symbol characters. Market-data order-book and instrument-view SQL enforce the same symbol
   rule. Valid boundary values remain accepted; malformed events fail consistently before
   consumers persist divergent views.
2. Risk rejects persisted REJECTED outcomes without a nonblank reason code and detail. Account
   SQL preserves the existing ReservationOutcome truth table: REJECTED requires both reasons,
   ACCEPTED/APPLIED have neither, and RELEASED retains its current semantics.
3. Account position creation validates the same canonical symbol rule as reservation creation,
   before reaching JDBC, through existing Account value semantics.
4. The existing Query JDBC public-seam tests run against PostgreSQL as well as H2. PostgreSQL
   evidence covers UUID/DATE round trips, actual ON CONFLICT insertion/update, maker/taker facts,
   complete Account reasons, reference installation, duplicate replay and transactional rollback
   of inbox/projection/checkpoint. The explicit PostgreSQL task fails when not configured rather
   than silently skipping. CI provisions a dedicated scratch database and rolls back/cleans only
   test-owned state.

## Ownership and validation

Matching/market, Account/Risk and Query tests are independent work slices. Root CI/scripts/docs and
final PostgreSQL lifecycle belong to integration. Tests use the existing public validator,
application/domain, JDBC and Flyway seams; no second production framework or generic test platform
is introduced. Service-owned versioned migrations remain the schema delivery path.

Run focused tests during each slice, followed by the complete Java suite and staticAnalysis.
Run the existing Flyway CI checks and the PostgreSQL JDBC task on a dedicated local PostgreSQL
instance, verify migration idempotency/validation and new SQL rejection cases, then perform
independent standards/spec reviews against `e78d7e0`. Docker/Kubernetes deployment is not part of
this follow-up. Do not infer deployment certification from these repository/local results.

## Initial implemented result and local validation

The shared validator and Market V3 now enforce one canonical instrument shape. Account position
creation reuses reservation symbol semantics. Account V11 and Risk V14 initially matched Java outcome
rules, including Unicode whitespace classification; no new RELEASED restrictions were introduced.
PostgreSQL probes cover malformed symbols, rejected/active reason combinations, tabs, ideographic
space and nonbreaking space behavior.

`postgresJdbcTest` runs the same 11 public JDBC contract tests used by H2, with UUID and DATE round
trips, actual PostgreSQL ON CONFLICT inserts/updates, maker/taker facts, long reason text, duplicate
replay, reference reinstall and a checkpoint failure after inbox/model writes. The rollback test
ends the fixture transaction before invoking the production transaction interceptor, so the result
cannot be attributed to an outer test rollback. The explicit task disables up-to-date reuse and
fails with missing connection settings; it does not silently skip PostgreSQL evidence.

The runner requires UTF8, creates a unique scratch database and records its ownership marker.
The test fixture verifies the database name, URL and marker before Flyway or any fixture mutation;
cleanup revalidates ownership before deleting exactly that scratch database. The final inventory
contained no remaining `simplematch_query_jdbc_*` database. Ordinary fixtures roll back per test.
The Query dependency change is one test-runtime Flyway PostgreSQL module, updated through scoped
dependency locking; catalog versions remain unchanged.

Local validation completed on 2026-10-16:

- Complete Java suite: 763 tests, no failures/errors/skips; Gateway certification fixtures: 3 pass.
- Global staticAnalysis: pass without PMD-rule edits or warning suppression.
- PostgreSQL JDBC suite: 11 tests, no failures/errors/skips, with PostgreSQL backend evidence.
- All 34 service migrations: Flyway install/repeat/validate, schema history isolation, index smoke
  and SQL probes pass. The final SQL probe file also passed after removing duplicate cases.
- Missing PostgreSQL settings: explicit task fails as expected rather than skipping the suite.
- Source/Markdown/migration-layout and diff checks pass.

One preexisting index-smoke assumption needed adjustment: an empty reference table may legitimately
use the day/symbol unique index instead of the artifact index. The check now independently confirms
that the declared artifact index exists, and permits the known valid alternate indexed plan.
This is access-path smoke coverage, not a performance benchmark or a new-index recommendation.

Standards/spec reviews found no blocking issue or missing requirement. Standards noted two duplicate
SQL probes; those duplicates were removed without adding a test abstraction. These are repository
and isolated local PostgreSQL results, not remote CI or live deployment certification.

## Canonical reason storage revision

The owner approved this narrower storage contract on 2026-10-17. Current Account writers produce
fixed rejection/release codes and messages; Risk persists reasons returned by Account. Neither
path accepts user-authored reason text. Reproducing Java's full Unicode whitespace classification
in database constraints is therefore unnecessary.

Acceptance for this revision:

- Account ACCEPTED/APPLIED reasons are exactly zero-length strings, not SQL NULL or whitespace.
  Existing null-to-empty construction remains; nonempty inputs are rejected rather than trimmed.
- Account REJECTED and Risk failure codes use `[A-Z][A-Z0-9_]*`. Application-owned detail validation
  continues to reject null/blank text and preserves valid text without trimming or truncation.
- New Account V12 and Risk V15 migrations replace their outcome CHECKs with the code grammar and
  nonempty detail checks. Existing NOT NULL columns remain. SQL no longer promises to duplicate
  Java's Unicode blank rules; application tests own that stronger text validation.
- Account RELEASED keeps its existing reason combinations and null-to-empty construction. No
  additional detail requirement, enum framework, cross-service policy helper, or data conversion
  is introduced. V11/V14 remain immutable historical migrations.
- Tests use the existing ReservationOutcome/AdmissionFailure public constructors, lifecycle
  callers, service Flyway tests and rollback-only PostgreSQL SQL probes. Valid fixed codes and
  messages, exact empty active reasons, invalid code grammar, NULL protection and unchanged
  RELEASED behavior are covered before the full Java/static-analysis and Flyway CI checks.

Account and Risk implementations are independent owned slices; shared SQL probes, documentation,
isolated PostgreSQL lifecycle and final two-axis review belong to integration. Existing outbox,
transaction ownership, retry and recovery paths are unchanged.

Revision validation completed on 2026-10-17:

- Account: 174 tests; Risk: 208 tests. Test-first failures reproduced the superseded behavior.
- Complete Java entrypoint: 847 tests plus 3 Gateway certification fixtures; staticAnalysis passed.
  The initial online invocation stopped on Maven DNS resolution; the cached offline invocation
  passed without dependency changes.
- All 36 service migrations passed the PostgreSQL 18.6 UTF8 Flyway CI runner, including repeat,
  validate, index/schema-history checks, SQL probes and all 11 Query PostgreSQL JDBC tests.
- The live catalog confirmed the simpler CHECK definitions and NOT NULL on all four reason
  columns. PostgreSQL probes reject trailing LF/CR codes, covering a known H2 regex difference.
- Standards and Spec reviews each found zero actionable issues. Layout, registry, cutover,
  Markdown and diff checks passed. The runner left no JDBC scratch database; the dedicated
  PostgreSQL was stopped and its owned data removed. These are local results, not deployment
  certification or remote CI.
