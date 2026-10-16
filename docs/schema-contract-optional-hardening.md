# Remaining schema contract hardening

The owner approved these four previously optional items after the canonical reason revision.
This bounded batch starts at `2f019ac`. Existing transaction-data compatibility remains out of
scope. Each schema change uses a new owner-scoped Flyway migration; historical migrations remain
unchanged. This work adds data-layer defenses and removes unused state, without claiming that
the existing artifact installer or current short FIX session names were faulty.

## Acceptance and ownership

| Owner | Scope | Acceptance |
| --- | --- | --- |
| Query | `services/query-service` | Active reference market rule, three prices and routing partition are mandatory. Rule identity is nonblank, price inequalities match the existing FINAL/ELIGIBLE artifact contract, and partition is 0..14. Control fixtures contain complete valid rows. Verified artifact install/reinstall still succeeds on H2 and PostgreSQL. |
| Query | `services/query-service` | Account lifecycle event identities are canonical lowercase hyphenated UUIDs, validated before inbox/projection/checkpoint mutation. Matching identities remain lowercase 64-character SHA-256 from the validated envelope. Text storage continues to represent both source formats; replay/conflict/rollback behavior is preserved. |
| Gateway | `services/quickfix-gateway` | Durable complete SessionID uses TEXT. A session assembled from valid components can exceed 256 characters and round-trip without truncation. Loaded SessionSettings components are checked against existing component-column capacities, including optional SubID/LocationID/qualifier, before starting the engine. Short current sessions still work. |
| Account | `services/account-service` | Confirm the release timestamp has no production reader/writer or independent current domain requirement, then remove the unused column. Preserve all existing created/updated timestamp checks, release semantics and lifecycle behavior. |
| Integration | Root SQL probes and documentation | PostgreSQL probes use complete reference controls, verify new NULL/range defenses, long session round-trip and removal of the unused timestamp. Run full Java/static analysis, Flyway CI plus Query PostgreSQL JDBC tests, and independent Standards/Spec review. |

Tests reuse existing public artifact-install, event projection, configured session and lifecycle
seams. No shared policy framework, migration runtime, new event format, replay compatibility
adapter or speculative indexes are introduced. Existing outbox/saga transactions, retries and
recovery ownership remain unchanged. Local PostgreSQL evidence is distinct from deployment
certification; no Docker/Kubernetes deployment is required by this batch.

## Implemented decisions

Query V3 uses the existing `FinalArtifactPriceBand` conditions: all three prices are positive and
`lower < reference < upper`, with no speculative maximum below BIGINT. The market-rule check uses
the database's native `\S` shorthand, not a copied Java Unicode character table. Reference fixtures
now supply complete facts. The canonical Account event-ID check compares the parsed UUID's
standard representation with the original input before obtaining a source position or claiming
the inbox; Matching's envelope-derived SHA-256 identity remains unchanged.

Gateway V5 widens only the complete delivery SessionID to TEXT. The existing lifecycle validates
every parsed session after DEFAULT inheritance and before acceptor creation: BeginString fits
8 characters, and each of the seven remaining components fits 64 characters. Optional components
remain optional. No artificial upper bound is applied to the complete serialized identity.

The release timestamp audit found only the historical declaration/check and archived field catalog,
with no production reader, writer or separate current domain requirement. Account V13 explicitly
drops the old combined timestamp CHECK, removes the unused column and re-adds the same named
created/updated CHECK, so DROP COLUMN cannot silently remove the remaining timestamp protection.

Existing application transaction boundaries, artifact installation, replay/conflict handling and
public test seams remain in use. Standards and Spec source reviews each found zero actionable
issues for this batch.

## Local validation record

Validation completed on 2026-10-17. Each worker first reproduced the missing behavior through
existing public tests, then ran its complete owning suite: Account 177 tests, Query 85 tests,
Gateway 192 tests and 3 certification fixtures, with no failures/errors/skips.

The complete Java entrypoint passed 902 tests plus the 3 Gateway certification fixtures;
global staticAnalysis passed using cached offline dependencies. No dependency, PMD rule or warning
suppression changes were needed. Migration layout, service registry, Account cutover, Markdown
links/regression checks and diff whitespace checks passed.

The isolated PostgreSQL 18.6 UTF8 Flyway CI runner passed all 39 versioned SQL migrations, repeat
migration, validate, owner history/schema isolation, index smoke, rollback-only SQL probes and all
20 Query PostgreSQL JDBC tests. History counts exclude Flyway's separate SCHEMA marker. The live
catalog confirmed reference NOT NULL fields, Gateway session_id TEXT and the absence of the unused
Account release column. Probes cover strict price boundaries, partition endpoints, NULL guards,
long session identities and retained created/updated protections.

Standards and Spec reviews each reported zero actionable findings. The JDBC runner left no scratch
database. The dedicated PostgreSQL instance was stopped and its owned data directory removed;
logs were retained. These results cover repository and isolated local behavior, not remote CI or
deployment certification.
