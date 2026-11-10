# S6 focused CI: retained requirements and selection audit

Issue [#203](https://github.com/WenHsuanYu/SimpleMatch/issues/203) owns focused business CI and
final program acceptance. Parent [#196](https://github.com/WenHsuanYu/SimpleMatch/issues/196)
defines AC-01 through AC-12. This record applies the 2026-11-02 owner amendment.
It records the P0 requirement review and the bounded P1 workflow corrections.
It does **not** report a new hosted business Kubernetes run or final S6 acceptance.

The implementation baseline is `ececf38c2297b89738b2f039fa6e81cf3bd99d81`.
The S5 complete-local PASS belongs to executable source
`1a3fa704f37873acacab54415ce794171fd4456a`.
The four changes between these commits are Markdown records. See the
[final S5 evidence](shell-test-reduction-s5.md#2026-11-09-final-s5-p6-evidence-and-handoff).

## P0: authoritative requirement owners

**Complete local** means `./gradlew verifyAll` with the retained `resting-buy`
scenario. **Selected CI** means only the tests actually invoked by each workflow.
One owner can prove several requirements within one deployment. A component test
cannot establish the additional behavior observed only in a deployed scenario.

| Retained requirement | Authoritative behavior and test owner | Existing hosted selection | Coverage boundary |
| --- | --- | --- | --- |
| R11, durable critical delivery during a PostgreSQL outage | `KubernetesCriticalDelivery` and `KubernetesConsumerRestart` in `RestingBuyKubernetesTest` | Java and CDC component checks; no deployed R11 job | Retain actual outage, rejection, offsets, eventual delivery and cleanup. Retire the older simultaneous consumer-Pod outage matrix. |
| R12, Gateway admission and close | `KubernetesGatewayAdmission` and `KubernetesTradingClose` in the same live test; Gateway policy tests | Java `test` and QuickFIX `certificationTest` | Component checks do not prove deployed Close Barrier completion. |
| R13, public FIX resting BUY | `RestingBuyKubernetesTest` FIX submission, durable SQL and singular Matching/Persistence/Account assertions | Java service and QuickFIX tests only | No hosted public FIX-to-Matching deployment claim. |
| R14, same-owner Gateway restart | `KubernetesGatewayRecovery` in `RestingBuyKubernetesTest`; Gateway WAL/session tests | Java and QuickFIX tests | Retain actual session recovery and singular delivery. Retire the duplicate Shell recovery runner. |
| R15, Matching replacement and replay | `KubernetesMatchingRecovery` in `RestingBuyKubernetesTest`; native replay and service inbox tests | Native CI and Java tests | Native replay does not prove a deployed replacement. Controlled duplicate delivery does not imply the restart caused redelivery. |
| R16, Market Data rebuild | `KubernetesMarketDataRebuild` through `KubernetesMarketData` in `RestingBuyKubernetesTest`; existing evidence verifier | Projection and Query Java tests | Service tests do not prove deployed Redis outage, rebuild and repaired public snapshot. |
| R17, public market-data stream recovery | `KubernetesMarketDataStreamer` through the same live owner; existing stream observer | Streamer Java tests | No hosted public gRPC disconnect and resubscription claim. |
| R18, authenticated Query and replay | `KubernetesQueryBusinessRead`, `KubernetesQueryExecution`, `KubernetesQueryActiveProcessing` and `KubernetesQueryReplay` in `RestingBuyKubernetesTest`; Query HTTP/JDBC tests | Java and Query Redis TLS/ACL tests | Hosted components do not prove real FIX crossing, zero-Query-Pod isolation or source-bound Query replay. |
| R19, Query policy replacement | `KubernetesQueryPolicyReplacement` in `RestingBuyKubernetesTest`; Query authorization tests | Java Query tests | Hosted components do not prove live old-socket fencing, two new replicas or Account Kafka catch-up. Synthetic Account lifecycle fixtures remain identified as synthetic. |
| R20, Outbox CDC and exact redelivery | `OutboxCdcIntegrationTest` through `verifyCdc`, backed by Testcontainers | CDC CI `verifyCdc` | Retired Kubernetes Risk CDC telemetry and Connect worker reassignment are intentional losses. This owner does not restore them. |
| R21, Query health during Redis loss | `KubernetesQueryHealth` through `KubernetesMarketData` in the resting-buy run; Query HTTP tests | Java and Redis TLS/ACL tests | Hosted HTTP tests do not prove both deployed Query Pods remained healthy during the outage. |
| R22, Matching crash/recovery and native integrity | `KubernetesMatchingCrash` and `KubernetesMatchingRecovery` in the resting-buy run; `nativeTest` CTest | Native CI and Java/Matching components | Retired whole-fleet, CPU-pinning and independent worker-stop benchmark claims stay retired. |

The other S0 requirements also have explicit dispositions. Their owners
remain distinct from the R11-R22 deployed business assertions:

| Requirement | Retained owner and CI selection | Scope decision or intentional retirement |
| --- | --- | --- |
| R01, rendered manifests | `manifestCheck` in Java, CDC and complete-local Gradle tasks | Pinned schema and necessary project constraints replace old static overlay snapshots; real installation still requires Kubernetes. |
| R02, Flyway and PostgreSQL | `verifyPostgres`, service/JDBC tests, Flyway CI | Retain versioned migrations and representative SQL checks. Retire redundant Shell Flyway wrappers and H2-based acceptance. |
| R03, transaction and Account v2 | Account/Risk Spring transaction, saga, gRPC and persistence tests in Java CI | Retire checklist-text and completed v1/v2 source-location checks, without retiring transactional safety. |
| R04, Matching topic safety | Retained topic validator/provisioner, native messaging checks and deployment configuration owners | Preserve RF/ISR, partition and producer contracts. Retire old cutover fixture matrices and static script-shape guards. |
| R05, durable outbox publication | `verifyCdc` and its `OutboxCdcIntegrationTest` in CDC CI | Preserve actual event identity and redelivery. Retire legacy Compose connector adapters and duplicate Shell CDC observers. |
| R06, source-bound images and runtime content | `KubernetesBuildInputs`, `KubernetesImages`, Java image integrity tests and narrow image transport Shell contracts | Preserve actual kind-node execution and digest checks. Retire duplicate build/report orchestration. Hosted business image-transfer measurements remain for P3. |
| R07, owned kind lifecycle | `VerificationRun`, `KindEnvironment`, ownership Jupiter tests and lifecycle CI contracts/smoke | Preserve exact cleanup, diagnostics and foreign-resource safety. No standalone historical lifecycle PASS reuse. |
| R08, resource accounting | `manifestCheck` for declarations and retained local-resource report operations | The owner retired the numeric host-capacity preflight and its Ruby calculator. Neither manifest requests nor prior RSS measurements prove hosted feasibility. P3 must measure the selected CI job. |
| R09, complete run selection and verdict | `verifyAllScope`, `verifyAll`, Jupiter XML and run-owned Kubernetes summary | Retire the 62-phase dispatcher, report adapters, synthetic PASS reuse and historical phase schema. |
| R10, focused diagnostics | Retained bounded run-owned diagnostics, `verifyCdc` and narrowly scoped operator tools | Retire the old CDC continuation, Risk CDC observer and standalone Java placement collector. No focused report may upgrade a complete-local verdict. |
| R23, external PostgreSQL | Explicit operator `postgresLiveTest`; local disposable DB verification is `verifyPostgres` | External read-only checks remain opt-in. Local CI does not certify a production PostgreSQL server. |
| R24, external FIX | QuickFIX `certificationTest` in Java CI, explicit external FIX operator task | External counterparty/session certification remains opt-in. Simulator and local FIX results do not prove an external exchange. |
| R25, fast local hooks and static analysis | Retained narrow pre-commit/Flyway checks and Java `staticAnalysis` | No expensive pre-push hook or new analysis rules are introduced. |
| R26, historical checklist shape | Retired archive checkers and their CI caller | No current independent behavioral requirement. R03 transaction invariants remain tested at their authoritative seams. |

The smaller `risk-command` live owner is `RiskCommandKubernetesTest` through
`verifyKubernetes -PkubernetesScenario=risk-command`. It checks accepted Risk
admission, durable outbox identity and exact Kafka payload bytes. It does not
run Matching, the public FIX Gateway or Query. S6 P2 may select it in a
disposable hosted kind job after measuring its actual resource requirements.
It must not be labeled as R11-R19 or full-local certification.

## P0: retirement and remaining Shell responsibilities

The [S0 170-file inventory](shell-test-reduction-s0/script-ledger.md) records
the original file-level dispositions. The [S5 final retirement groups](shell-test-reduction-s5.md#2026-11-06-p5b-coherent-operator-and-aggregate-retirement)
record the deleted callers, fixtures, duplicate implementations and deliberate
verification losses. These decisions remain in force:

| Responsibility group | Decision | Remaining owner or intentional loss |
| --- | --- | --- |
| Old R11-R15 Shell business runners, fixture matrix and Ruby verdicts | Retired with their dedicated callers and contracts | Live Jupiter owner and service tests; no separate all-partition or simultaneous-Pod-failure certification |
| R16-R19 Shell/Python Query and Market Data Labs, collectors and compatibility result formats | Retired with their dedicated consumers | Existing Java live owner and focused HTTP/JDBC/evidence tests; no second policy-approval authority or legacy read-matrix reporter |
| R20 Kubernetes Risk CDC observer and Connect task reassignment | Intentionally retired | `verifyCdc` tests real broker/connector publication contracts; deployed Connect reassignment and Risk CDC telemetry are no longer tested |
| R22 extra E2E and native capacity wrappers | Retired | Existing Native CTest and live Matching owner; no new throughput, pinned-CPU or production HA claim |
| Former 62-phase full-local runner, historical PASS reuse and static source-shape checks | Retired with phase adapters, wrappers and dedicated tests | Gradle `verifyAll`, `verifyAllScope`, service tests, Testcontainers, CTest and live Jupiter; no old phase IDs or Compose-only PASS |
| Remaining Shell scripts | Retain narrow, actively consumed host operations | Local image, kind, resource, Flyway/topic and operator operations. The lifecycle CI Shell contracts test these utilities, not trading behavior. |

The tracked inventory at P0 is **34 `.sh` files / 6,896 physical lines**.
The newline count is 6,895 because one final line has no newline.
The frozen baseline is **170 files / 34,923 lines**. No tracked `.py` or
`.rb` file remains. The current count meets both 80% design limits:
34 files and 6,984 physical lines. The original reductions came from deleting
obsolete responsibilities, not moving Shell into YAML, Gradle or Java strings.
The P1 changes add no business logic, interpreter program or test implementation.

## P0/P1: CI selection and ownership

| Workflow | Retained task or operation | Current selection and S6 action |
| --- | --- | --- |
| `ci-java.yml` | `manifestCheck`, `staticAnalysis`, `test`, Gateway `certificationTest`, Query `redisSecurityTest` | Push, PR and manual; P1 adds `services/market-data-projection/**` to both event path filters. |
| `ci-cdc.yml` | `manifestCheck`, `verifyCdc` | Push, PR and manual for CDC, shared contracts and dependency infrastructure. The old CDC continuation is not invoked. |
| `ci-flyway.yml` | Dependency contracts and `verifyPostgres` | Push, PR and manual for database-owning services, SQL fixtures and Gradle infrastructure. Includes Projection already. |
| `ci-native.yml` | Native CMake build and CTest | Push, PR and manual for Matching, CMake, protocol and native fixtures. |
| `ci-local-resource-lifecycle.yml` | Existing Shell utility contracts and kind registry/resource smoke | PR and manual only. P1 bounds the `contracts` job. Neither job executes a deployed business scenario. |
| `dependency-submission.yml` | Dependency graph submission | Push and manual; it is not a business assertion owner. |

The current service, dependency and native workflows already run separately.
Splitting the remaining fifteen-owner Kubernetes `resting-buy` topology into
jobs would change the behavior under test. The [approved CI scope decision](verification-ci-scope-decision.md)
therefore makes complete hosted CI optional. P2 must add push-selected focused
business kind coverage through the existing Gradle owner. Source, shared
infrastructure, workflow and test changes must trigger all affected retained
selected suites. P2 must test its final path filters on GitHub.

## P0 handoff against AC-01 through AC-12

| Acceptance | Current evidence and remaining gate |
| --- | --- |
| AC-01 | S0 baseline dispositions, S5 final retirement groups and the R11-R22 owner table above. P0 owner review complete; P4 will check final surviving callers after all changes. |
| AC-02 | Baseline 170/34,923 to 34/6,896, zero tracked Python/Ruby. Recount on final S6 source. |
| AC-03 | S5 retirement audit records duplicate removal and no concealed language relocation. Repeat the audit only for S6 additions at P4. |
| AC-04 | S5 executable source `1a3fa704` passed `verifyAll` with retained owners on 2026-11-08. |
| AC-05 | `verifyAllScope` blocks excluded tasks, filtered/dirty source and altered complete-local profiles. Live verification uses fresh run and image identities. |
| AC-06 | Final S5 PASS used the dedicated cluster with exact cleanup. Disposable lifecycle has historical #200 evidence and existing tests; no new final-source disposable full-Live claim. |
| AC-07 | Existing JUnit/Gradle and `VerificationRun` report owners require named, non-skipped tests and four-field Kubernetes PASS. Hosted cancellation evidence remains for P3. |
| AC-08 | Spring-managed Jupiter, existing native tests and dependency Testcontainers owners remain authoritative. No new framework is required. |
| AC-09 | Existing pinned manifest owner is `manifestCheck`, selected in Java and CDC CI as well as `verifyAll`. |
| AC-10 | Open for P2/P3: push-selected business kind run, selected suite results, measured CPU, memory, disk, duration and image transfer. Complete hosted CI is optional. |
| AC-11 | Existing P6 PASS may be reused only if later changes leave runtime/build inputs, test definitions, complete-local selection and acceptance semantics unchanged. Original identities must stay attached to the original run. |
| AC-12 | P0/P1 document and CI alignment begins here. P4 must reconcile all active runbooks, report readers and final Issue evidence before closure. |

Original P6 evidence is run `6364220d-c45a-43e2-9100-daf098e78046` at
`out/verification/kubernetes/7458c622-11ad-4cdd-8f83-69bc519582ef/`.
Its `summary.json` records `status`, `assertions`, `diagnostics` and `cleanup`
as `PASS`. The runtime fingerprint is
`025ade2427eaa7bed00bc499a46bb9515662c9d7b742744947cc74a8c659c954`.
The verification fingerprint is
`24efd110a5c83c0438c32740e180d29faaf889f284759328803d5cc901fabbd7`.
P0/P1 change only Markdown and GitHub Actions workflow definitions.
Neither class of file contributes to these fingerprints. P4 must confirm the
final diff, current acceptance semantics and image identities before reusing
this evidence. No historical result is relabeled or upgraded.

**S6 state:** P0 owner/disposition review and P1 workflow maintenance are
separate from final acceptance. P2 must implement and execute selected hosted
business CI. P3 must measure resources, set grounded execution budgets and
prove failure/cancellation diagnostics and exact owned cleanup. P4 must run
selected CI on final source, publish evidence and limitations, and complete
the final AC-01 through AC-12 audit. Keep #203 and #196 open until then.
