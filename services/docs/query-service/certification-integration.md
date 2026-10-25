# Query certification consumers — QS1-07

Scope: [#194](https://github.com/WenHsuanYu/SimpleMatch/issues/194), first collector/placement
integration slice. **Final Phase 1 deployed acceptance remains incomplete.** This slice changes
tools, CI and documentation; it does not add Client ownership, Account provisioning, new grants,
Gateway/OAuth/mTLS, Redis optimization or production readiness.

The separately approved [Client/Account onboarding targets](../../../docs/plans/client-account-onboarding.md)
remain later work. Their two-Client/four-Account preset does not replace this slice's existing
synthetic X/Y and five-Principal policy.

## Acceptance and public seams

- Existing collector functions use the pre-deployed private fixture bundle over direct loopback
  HTTP. Business reads select Client A for X or Client B for Y, Market selects no-grant D, and
  full Freshness selects Operations. These are fixture aliases, not business Client registrations.
- Query authenticates/authorizes independently. An Account argument selects the expected fixture
  and client credential; it is not trusted ownership or permission evidence at the server.
- Query read/operations bearer tokens stay out of command arguments, shell traces, output and
  reports. Only the existing
  provisioner creates credentials before deployment; every replica must use the approved policy.
- The public FIX cross fixture uses pre-approved X/Y IDs. SQL selection requires the exact
  recorded BUY Order/X pair and matching parent/instrument/day. Missing or inconsistent data must
  not select an unrelated newer Execution or cause grants to be broadened.
- The active IOC uses Y, whose daily BUY limit is first seeded for that probe. Its reservation
  activity does not change X's baseline Account summary. These remain disposable test seeds,
  not idempotent production provisioning.
- Typed HTTP collection rejects malformed JSON, old embedded checkpoints, wrong Account/Order
  collections and wrong Market identity. Protected responses require the actual `no-store`
  directive. Redis entries never establish these identities or grants.
- Placement uses Operations for `/api/v1/freshness`; minimal health stays anonymous. It has no
  auth-disable or anonymous-Freshness fallback. Per-request timeout remains distinct from the
  observer's supported total budget.

Loopback HTTP tests use an independent credential-checking TCP server to test **client tools**.
They do not replace actual Spring Security FilterChain/PostgreSQL tests from QS1-01–05.
PostgreSQL collector tests execute real selection SQL through the existing external database
adapter seam. They require the runner-owned database marker and current Flyway migrations; they
delete only their newly generated fixture rows and never initialize product schemas ad hoc.

## Entry points and compatibility

Both `scripts/run-query-service-certification.sh` and
`scripts/run-local-java-placement-serving-check.sh` now require
`--query-fixture-directory PRIVATE_PATH`. The bundle is the owner-private output of
`provision-security-fixtures.py`; credentials remain outside Git. The standalone Query runner
accepts `SIMPLEMATCH_QUERY_CERTIFICATION_FIXTURE_DIRECTORY`; placement accepts
`SIMPLEMATCH_JAVA_PLACEMENT_QUERY_FIXTURE_DIRECTORY` as equivalent private path inputs.

The bundle must already match the serving Query policy and the FIX Account allowlists before a
real run. The tools neither register Principals nor change access policy. The Query runner checks
the Operations identity before seeding/releasing its FIX fixture. Rebuild remains independent.
The full-local input producer does not yet automatically supply all Query/FIX security material;
completing secured deployment integration is still #194 work.

The replay verdict declares `contractVersion: query-security-phase1/1`, validates minimal business
envelopes in baseline/Redis-outage/rebuilt snapshots, and reports `postgresReadsDuringRedisOutage`
instead of the old fallback name. It retains the existing deterministic replay, operations
checkpoints and trading-isolation/active-processing conditions.

Placement report schema 2 records `query-operations-read` versus `anonymous-health`, checks linked
Freshness bodies, and rejects schema 1 as current evidence. Reports stay diagnostic-only. Historical
schema-1 and #137 reports keep their original source/scope; no report is rewritten or relabeled.

## Supersession of closed #137 expectations

| Historical expectation | Current Phase 1 contract |
| --- | --- |
| Order response cache hit/fill/fallback/key presence | Authorized PostgreSQL-only Order read; no response-cache read/write. |
| Executions response cache hit/fill/fallback/key presence | Authorized existing-parent PostgreSQL collection; no response-cache read/write. |
| Account Summary response cache hit/fill/fallback/key presence | Explicit Account Grant and PostgreSQL snapshot; no response-cache read/write. |
| Market Reference response cache hit/fill/fallback/key presence | Authenticated PostgreSQL artifact read; no response-cache read/write. |
| Full embedded business checkpoints | Minimal `freshness.projectionState`; full checkpoints only on operations-read Freshness. |
| Redis-outage fallback evidence | Same authorized PostgreSQL business view while Redis is unavailable; this is not cache-only availability. |

The current standalone runner has no remaining four-response-key existence assertion. Independent
Redis TLS/ACL/namespace tests and durable replay/checkpoint requirements remain applicable.
Closed #137 is not reopened or invalidated; #138 is historical platform context, not this slice's
parent or proof of Query HTTP authorization.

## Requirement-to-test mapping and remaining gates

| Requirement | Existing/new executable evidence | Limit or remaining work |
| --- | --- | --- |
| INV-01/03, A3/A4: identity, policy, routes, challenges and denial | `QueryHttpSecurityIntegrationTest`, `QuerySecurityPolicyTest`, `QuerySecurityStartupTest`, `QueryDirectHttpTest` | Reuse actual FilterChain suites; client-tool fixtures alone are insufficient. |
| INV-02: grants and trusted parent ownership | `QueryAccountSummaryHttpTest`, `QueryOrderHttpTest`, `QueryExecutionsHttpTest` and snapshot tests | Deployed multi-replica cross-route matrix still required. |
| INV-04, A1: cache non-use/identity and minimal responses | Existing business HTTP suites with cache probes; `collector-http-contract.py`; `verdict-contract.sh` | Actual legacy Redis preseed across the deployed surface remains pending. |
| INV-05/06: independent operator/FIX/direct trust | Existing FilterChain/direct/rebuild suites; collector route allowlist; independent operator route unchanged | Preserve full deployed operator and negative-credential evidence in aggregate acceptance. |
| INV-07, A3: revoke/disable/rollback | #193 `runtime-r13` at its recorded source; unchanged lifecycle contracts | Historical focused PASS is not a newly executed aggregate; expanded Order/Executions lifecycle checks remain pending. |
| INV-08: no trading dependency on Query | Existing active-liveness/isolation/verdict contracts | Fresh secured full-runtime IOC/isolation and separate rebuild/replay evidence remain required. |
| A4, LAB consumer compatibility | `collector-http-contract.py`, `collector-postgres-contract.py`, placement contract | Tests prove tool behavior/real SQL, not NetworkPolicy or production transport. |

INV-09 and later Gateway/certificate portions of INV-10 retain their independent phase approvals.

Focused checks:

```bash
python3 scripts/end-to-end/query-service/tests/collector-http-contract.py
bash scripts/end-to-end/query-service/tests/evidence-contract.sh
bash scripts/end-to-end/query-service/tests/active-liveness-contract.sh
bash scripts/end-to-end/query-service/tests/verdict-contract.sh
bash scripts/test-local-java-placement-serving.sh
./gradlew -q :services:query-service:test
# Supply the existing runner-owned QUERY_SERVICE_JDBC_TEST_* environment first.
./gradlew -q :services:query-service:postgresJdbcTest
python3 scripts/end-to-end/query-service/tests/collector-postgres-contract.py
./gradlew -q staticAnalysis
```

The SQL contract is included after JDBC checks in `run-flyway-ci-checks.sh`; HTTP/placement contracts
run in Local Resource Lifecycle CI. Before expensive deployed acceptance, classify every required
phase through the existing planner/evidence contract. No required phase may be silently skipped,
and no focused diagnostic or tool PASS may be promoted to aggregate Phase 1 PASS.

## Observed verification for this slice

Consumer implementation source: `0b04ad9` (2026-10-25). The later documentation commit does not
change these executable inputs. Local evidence is retained under
`out/verification/issue-194-certification/`; this is a tool integration record, not a complete
deployed Phase 1 run.

| Check | Observed outcome | Boundary |
| --- | --- | --- |
| New collector/placement HTTP contracts | 9 passed | Actual loopback credential-checking TCP fixture, including slow streaming and total deadline; not a Spring server. |
| New SQL selection contracts | 4 passed | Actual PostgreSQL 18.6, existing Flyway migrations and owned scratch database. |
| Existing Python contracts | 42 passed | Fixture/policy/lifecycle tool regression suites. |
| Shell contracts | Query scripts, complete resilience-consumer suite and Flyway helper contracts passed | No deployment or NetworkPolicy certification. |
| Ordinary Query task | Gradle up-to-date; retained 355-test result, zero failures/errors, 5 skipped | Unchanged Java inputs; do not describe these as freshly executed. |
| PostgreSQL JDBC/HTTP task | 217 passed, no failures/errors/skips | Fresh owned PostgreSQL run; overlaps ordinary Query scenarios. |
| Repository static analysis | Passed | Existing rules unchanged; no new suppressions. |
| Standards / Spec review | Zero remaining findings on each axis | P3 transient documentation wording and reproduced P2 whole-request timeout corrected. |

The initial sandbox scratch could not persist a reachable server across commands; the owned host
route supplied the PostgreSQL evidence instead. Both exact scratch directories were removed after
the marker-verified server stopped. Docker resources, build caches and the canonical kind cluster
were inventoried and retained. No real Client/Account registration, Account/FIX source change or
Kubernetes deployment was performed. Final-head GitHub CI and its issue progress record are
reported separately; pending aggregate gates above remain pending.
