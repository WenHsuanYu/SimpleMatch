# Query certification consumers — QS1-07

Scope: [#194](https://github.com/WenHsuanYu/SimpleMatch/issues/194), Local Lab Phase 1 integration
and certification. The source-aligned runtime gates below passed; final-head CI and delivery are
recorded on the owning issue. This work changes
tools, CI and documentation; it does not add Client ownership, Account provisioning, new grants,
Gateway/OAuth/mTLS, Redis optimization or production readiness.

**Current S5 execution owner:** the selected `RestingBuyKubernetesTest` through
`./gradlew verifyKubernetes -PkubernetesScenario=resting-buy --no-daemon`.
Issue #202 retired the historical standalone Query Shell runner, the Python R19
Local Lab, and their dedicated contracts. The commands and legacy evidence
formats described below apply only to the original #194 source. See
[current Query deployed verification](../../../docs/verification/query-service-certification.md).

The separately approved [Client/Account onboarding targets](../../../docs/plans/client-account-onboarding.md)
remain later work. Their two-Client/four-Account preset does not replace this slice's existing
synthetic X/Y and five-Principal policy.

Issue #202 later retired the obsolete Redis response-envelope poisoning
and comparison check. Current Query evidence verification has eleven checks.
The historical twelve-check PASS and four poisoned entries recorded below
belong to the earlier #194 source and have not been reclassified or rerun.
The actual Redis-outage PostgreSQL read verification remains required.

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
The retired Python/PostgreSQL collector executed standalone scratch-fixture selection SQL.
Current Query JDBC and HTTP suites use their own Testcontainers databases and Flyway migrations.
The selected Kubernetes owner observes business identities from real FIX-backed source events.

## Historical entry points and fixture policy

`scripts/end-to-end/query-service/run-certification.sh` formerly required
`--query-fixture-directory PRIVATE_PATH`. This runner is retired in S5.
The bundle was the owner-private output of
`provision-security-fixtures.py`; credentials remain outside Git. The Query runner
accepted `SIMPLEMATCH_QUERY_CERTIFICATION_FIXTURE_DIRECTORY` and the common
`SIMPLEMATCH_CERTIFICATION_QUERY_FIXTURE_DIRECTORY` fallback. Issue #202 retired
the separate placement runner; Query now checks selected Pod health and identity
within the selected Jupiter Redis outage.

The bundle must already match the serving Query policy and the FIX Account allowlists before a
real run. The tools neither register Principals nor change access policy. The Query runner checks
the Operations identity before seeding/releasing its FIX fixture. Rebuild remains independent.
The full-local input producer now consumes a composite synthetic bundle as described below.
Secured multi-service deployment and complete cross-route acceptance have separate evidence below.

## Complete deployed acceptance

**Historical description of the retired Query Lab and full-local runner.** The current
execution owner is the selected `verifyKubernetes` `resting-buy` Jupiter scenario. The
Python fixture, read-matrix, and legacy replay tools are no longer executable.

The controlled lifecycle runner now seeds explicit migrated Query-owned Order/Execution DML:
each X/Y Account has one parent with an Execution and one with an empty collection, plus a
durably verified absent parent. These rows prove HTTP ownership/authorization; they are not
Matching publication or replay evidence. Account lifecycle publication, captured Kafka boundaries,
persisted checkpoints and restart without reset remain independent recovery evidence.

Every serving Query Pod receives the full five-Principal read matrix at V1, after C-to-Y
revocation at V2, and after D disablement at V3. C-to-X and B-to-Y remain allowed. The shared
verifier checks canonical response identities, all Execution members, minimal business envelopes,
Operations-only full checkpoints, policy-version headers and `no-store` on successful reads.
The lifecycle runner rejects uncommitted runtime inputs before Docker access; its phase version
and input manifest include the fixture/HTTP modules and regression tests.

The separate full trading runner uses the real public FIX cross fixture for X/Y Orders,
checks both Query replicas directly, and independently proves the selected absent parent in
PostgreSQL. Before business reads it seeds all four obsolete `query:v1` response keys with
old full-checkpoint envelopes and mismatched fixture content. Typed HTTP reads must succeed
from durable projections while those bytes remain unchanged. These are deliberately obsolete
cache fixtures, not requirements to re-enable Redis. Redis outage, Query outage/trading
independence and deterministic replay retain their separate gates.

After explicitly enabling the independent rebuild adapter, all five Query credentials must fail
rebuild, and the operator credential must fail Account reads as either a Bearer or operator
header. The subsequent legitimate reset/replay proves operator functionality. Generated Query
and Gateway operator tokens travel through stdin and immutable Secret references rather than
process arguments or captured Deployment environment values. The Query verdict contract
`query-security-phase1/2` requires matrix, obsolete-cache and operator-separation evidence in
addition to the existing recovery gates. Each scenario must pass in its own runtime; focused
lifecycle PASS is never relabeled as full-system certification PASS.

## Secured full-environment inputs

**Historical Python bundle interface (retired by Issue #202).** The requirements below
document the prior synthetic Lab material. The selected Java/Jupiter owner creates its own
private FIX TLS and Query credentials. Its tests verify store privacy, mutual TLS,
credential digests, replacement revocation, and the authorization matrix. It does not
accept or validate a former Python certification bundle.

`provision-certification-fixtures.py` reuses the existing five-Principal Query provisioner,
then generates independent FIX Logon credentials and separate self-signed server/client PKCS12
identities. The FIX allowlist contains exactly the same X/Y Accounts, on the existing acceptor
SessionID `FIX.4.4:SIMPLEMATCH->CLIENT`. This does not model two business Clients or replace
the separately planned onboarding model. Client and server truststores contain the exact
approved synthetic peer certificates; no production CA, revocation system or Query incoming
mTLS is introduced. Query, FIX and rebuild credentials retain separate purposes.

The complete bundle is owner-private. Query's `application.properties` contains credential
verifiers; FIX's private properties contain the Logon and store passwords required by the
existing Gateway configuration. No credential or private store belongs in Git, image layers
or public evidence. `certification_security.py` uses the shared read-only
`policy_bundle.py` parser to validate the complete synthetic Query policy, including exact
Principal/credential mappings, allowed grants and Operations permission. Inspection neither
approves a policy nor activates one. The consumer checks private file permissions, FIX
configuration coherence and credential expiry, decrypts all four PKCS12 stores with their
configured passwords, and verifies matching keys/certificates, exact peer trust, validity,
TLS purposes and the loopback server identity before using the bundle. JDK `keytool` and
OpenSSL are required; cryptographic processing uses those tools rather than custom crypto. Its client
launcher passes FIX secrets in the child environment, never command arguments or shell `eval`.

```bash
./gradlew verifyKubernetes -PkubernetesScenario=resting-buy --no-daemon
```

The command above selects the deployed R18/R19/R21 verification. The former
private-bundle producer and validator were retired with their last executable callers.

The retired Query Shell runner created its X/Y trading fixtures once and
required a fresh disposable namespace after failure. It recorded five
quiescent isolation samples, a report no longer reproduced by the selected
Jupiter test. The current test owns fixture setup and exact namespace cleanup.

Full Kubernetes deployment requires this bundle through the common environment variable or
`--query-fixture-directory`. Help, dry-run, Compose-only and Matching-fleet-only profiles do
not require a bundle and do not prove Query security. Each full deployment creates immutable
`query-service-secrets` and `quickfix-gateway-secrets` with their existing PostgreSQL key plus
their respective `query-service.properties` / `quickfix-gateway.properties` file keys;
`quickfix-gateway-fix-tls` holds only the server key
store and approved client truststore. All Query replicas use the same existing named Spring
Environment Secret source. Store mounts and the FIX mTLS requirement are unchanged.
The service-named keys are required by the installed Spring Cloud Kubernetes multi-key Secret
parser; a generic `application.properties` key alongside `postgres_dsn` is skipped. A test invokes
the installed parser for both application names before deployment. Resume rejects additional
profile/property keys that could override the approved policy.

Query and Gateway also explicitly select `spring.cloud.kubernetes.secrets.read-type=SINGLE`.
The framework's default BATCH mode requires Secret LIST permission, while the existing service
accounts deliberately permit only GET on their named Secrets. ConfigMap `read-type` does not
set Secret `read-type`. The maintained locator regression reads both actual service YAMLs,
binds their settings and invokes the real Kubernetes Secret locator/client against an HTTP API
that permits named GET and rejects LIST. Its negative omits only the Secret read mode to
reproduce the absent security configuration. This preserves the existing RBAC boundary.

The one-hour Query fixture lifetime is unchanged. Cold builds can exhaust it: input validation
fails closed again before Kubernetes input mutation, rather than silently renewing credentials
or broadening grants. Prepare/reuse validated images first when needed. Never use an expired
bundle, silently replace an active run's identities, or treat a retained credential as production
provisioning. The launcher does not redact arbitrary child output; callers must retain the
existing credential-safe logging contract and must not run environment-dumping commands.

The run context and `kubernetes-inputs` fingerprint bind the declared private input digest.
The context also binds a separate digest of the server-side policy/store subset. Focused
CDC/Connect continuations recompute it from decoded immutable Kubernetes Secrets, without
needing client credentials or private keys on disk, and reject changed or competing settings.
The producer recomputes that validated digest before its first Kubernetes write and rejects
post-preflight changes instead of deploying material different from the recorded inputs.
Resume verifies the exact immutable deployed policy/store bytes and refuses changed inputs;
it does not overwrite serving policy. Controlled revocation still follows #193's separate
Block/Drain/Activate/Verify/Resume authority and anti-rollback contract. Hashes establish input
identity, not business approval or proof of the policy actually serving HTTP requests.

`static-phase1-deployment-contracts` and `kubernetes-inputs` definition versions are now 3.
The static phase includes the fixture and input-producer contracts, and Kubernetes inputs
depend on that gate. The source/input fingerprints include their implementations and tests.
Older phase evidence cannot stand in for the new semantics.

Current Java checks for the retained behavior:

```bash
./gradlew :tools:kubernetes-tests:test --tests '*KubernetesFixCredentialsTest'
./gradlew :tools:kubernetes-tests:test --tests '*KubernetesQueryCredentialsTest'
```

The selected FIX test exercises generated PKCS12 material through a real loopback TLS
handshake, including rejection of a client without a certificate. The former
`certification-inputs-contract.py` was retired with the Shell aggregate's private
`apply_local_kubernetes_inputs` adapter. That old synthetic `kubectl` seam is no longer a
current verification owner.

The retired Shell replay verdict declared `contractVersion: query-security-phase1/2`, validated minimal business
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

The retired standalone runner had no remaining four-response-key existence assertion. Independent
Redis TLS/ACL/namespace tests and durable replay/checkpoint requirements remain applicable.
Closed #137 is not reopened or invalidated; #138 is historical platform context, not this slice's
parent or proof of Query HTTP authorization.

## Requirement-to-test mapping and remaining gates

| Requirement | Existing/new executable evidence | Limit or remaining work |
| --- | --- | --- |
| INV-01/03, A3/A4: identity, policy, routes, challenges and denial | `QueryHttpSecurityIntegrationTest`, `QuerySecurityPolicyTest`, `QuerySecurityStartupTest`, `QueryDirectHttpTest` | Reuse actual FilterChain suites; client-tool fixtures alone are insufficient. |
| INV-02: grants and trusted parent ownership | `QueryAccountSummaryHttpTest`, `QueryOrderHttpTest`, `QueryExecutionsHttpTest` and snapshot tests | Final deployed matrices cover both replicas, including lifecycle Order/Executions fixtures. |
| INV-04, A1: cache non-use/identity and minimal responses | Existing business HTTP suites with cache probes; selected Jupiter Query business reads | Four actual obsolete Redis entries were ignored in historical #194 evidence. |
| INV-05/06: independent operator/FIX/direct trust | Existing FilterChain/direct/rebuild suites; per-Pod matrix; enabled operator boundary | All nine deployed negative credential checks passed; independent authorized rebuild succeeded. |
| INV-07, A3: revoke/disable/rollback | Final-source `lifecycle-final` proof, separate from historical #193 | V1/V2/V3, rollback rejection, failed activation, no-reset recovery and fresh-run isolation passed. |
| INV-08: no trading dependency on Query | Full Query verdict and actual public IOC/isolation evidence | Public FIX processing with zero Query Pods and separate deterministic rebuild/replay passed. |
| A4, historical LAB consumer compatibility | Query HTTP/JDBC authorization tests and selected Kubernetes FIX-backed read/replay | The standalone Python-to-Shell SQL collector and its scratch-fixture selection contract are retired. No separate LAB collector verdict remains. |

INV-09 and later Gateway/certificate portions of INV-10 retain their independent phase approvals.

## Accepted Local Lab evidence

The retained acceptance set is under
`out/verification/issue-194-certification/completion/`. Older local runs were removed at the
owner's request; closed issues and their original contracts/history were not rewritten.

| Gate | Actual result | Retained evidence |
| --- | --- | --- |
| Complete full-local profile | PASS; 62 phases, no SKIP: 32 EXECUTE, 20 REUSE, 10 REVALIDATE | `full-local-r3/report.md`, `plan.json` and phase source evidence |
| Complete Query certification | PASS; all 12 checks, 100 per-Pod matrix observations, four obsolete cache entries, nine operator negatives | `query-runtime/verdict.json` and linked observations |
| Placement/serving | PASS; two unchanged Query Pods, 60-second Redis outage, authenticated Operations and minimal anonymous health | `placement/java-placement-serving.json`; diagnostic-only scope |
| Controlled policy lifecycle | PASS; two disposable runs, 560 matrix observations, V2/V3 transitions, rollback/failure fencing and no-reset catch-up | `lifecycle-final/runtime/verdict.json` and event proofs |
| Query/Gateway suites and static analysis | Query: 361 tests, zero failures/errors, five skipped; Gateway: 251 tests, zero failures/errors/skips; static analysis PASS | Local test summaries and final-head CI on #194 |
| Actual dependency integration | 217 HTTP/JDBC tests and five collector/fixture tests against PostgreSQL; six Redis TLS/ACL/namespace tests, all without skips | PostgreSQL and Redis result summaries |

Runtime execution used `95273ed` before this reporting-only amendment. The published implementation
anchor is `4b9a602`; their runtime, verifier, test and build inputs are identical. Final delivery
adds only reporting/operational documentation, with source compatibility recorded alongside the
evidence. Local Lab transport, revocation and synthetic-policy results do not establish production
readiness, a hard revocation SLA, OAuth, Query incoming mTLS or Phase 2 cache performance.

Current focused Java checks:

```bash
./gradlew :tools:kubernetes-tests:test
./gradlew :tools:recovery-evidence-verifier:test
./gradlew -q :services:query-service:test
# The compatibility task selects the canonical Query suite once.
./gradlew -q :services:query-service:postgresJdbcTest
./gradlew -q staticAnalysis
```

The Query suite retains JDBC, FilterChain, and HTTP contracts with Testcontainers databases.
`./gradlew verifyPostgres` invokes the same Query owner without the retired collector.
Before expensive deployed acceptance, classify every required
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
