# Query Security Decision Register

Status: **Historical approval and planning record. Later-phase mechanisms remain separately gated.**
Authority: the user-supplied Principal Engineer Review Resolution and A1–A7 addendum.
Scope is documentation only; the addendum below supplements rather than replaces R1–R8.
See the [revised specification](security-external-access-spec.md) and
[implementation/evidence plan](../../../docs/plans/query-security-cache-implementation.md).

Phase 1 authorization and ticket-state statements below describe their recorded planning rounds.
For current delivery status and evidence, see [QS1-07 / #194](https://github.com/WenHsuanYu/SimpleMatch/issues/194).

## Status vocabulary

- **Approved direction:** an architectural rule authorized by the resolution, not delivered code.
- **Required / unresolved:** a required decision or result whose values/mechanism are not approved.
- **Candidate:** recommendation requiring evaluation and approval; not an SLA or capability.
- **Source verified:** inspected implementation fact; runtime behavior still needs separate evidence.
- **Runtime accepted:** requires a named report, revision, environment, outcome and limitations.
  No entry in this revision has this status.

## R1–R8 resolution matrix

| Review | Disposition and revised rule | Phase / evidence gate |
| --- | --- | --- |
| R1 Transport | Accepted. Synthetic-credential loopback/port-forward lab is application evidence only. Real credentials require encrypted server-authenticated transport; native HTTPS is a candidate. | Phase 1 application gate separate from staging/production transport gate. |
| R2 Cache disclosure | Accepted, subsequently extended by user simplification: Account/Order/Executions/Market Reference all use authorized PostgreSQL only; bypass response-cache reads/writes and revise all four key-presence assertions. | Phase 1 real HTTP and PostgreSQL gate; old Redis envelopes cannot serve responses. |
| R3 Proxy identity | Accepted, mechanism conditional. Prefer external mTLS, Gateway-specific backend mTLS and RFC 8705; XFCC SanitizeSet is unverified candidate. | Phase 5 entry capability spike; reject A-cert/B-token, forged metadata, wrong backend peer, missing cnf and direct bypass. |
| R4 Revocation | Accepted; hard SLA deferred. Versioned local snapshot; explicit start/effective/in-flight/failure/rollback rules and executable emergency isolation. | Phase 1 contract; multi-replica evidence before claiming effective revocation. Five minutes remains candidate operational SLO. |
| R5 Cache concurrency | Accepted, strengthened. Compare maintenance with generation; no older fill overwrites newer cached resource; CAS alone does not prove client monotonicity. Account stays PostgreSQL-only pending semantics/revision evidence. | Phase 2A baseline, 2B design approval, 2C selective implementation and correctness/performance gates. |
| R6 Disclosure | Accepted. Full internal checkpoints may remain; external DTO/freshness needs approval. No automatic v2 for unpublished API. | Before external publication; verify embedded metadata as well as standalone freshness route. |
| R7 Routing | Accepted. Gateway and application allowlists; verify path/method/normalization, route attachment RBAC, additive policies and actual CNI behavior. | Phase 3 routing, Phase 5 end-to-end boundary. |
| R8 Migration | Accepted. Explicit authentication modes, approved access-token profile, no JWT-failure fallback. Opaque only if needed; final binding has no weaker legacy path. | Phase 4 token identity; Phase 5 certificate consistency and OD-15 retirement. |
| Additional Account finding | Accepted as external-account API blocker. Last reservation-event projection is not balance aggregation; verify event order/revision/replay before account monotonicity or authority claims. | Phase 0 semantic decision; Account Cache and external account-status release remain gated. |

## D1–D5 decisions

| ID | Approved direction | Still unresolved |
| --- | --- | --- |
| D1 | Stable Query client principal independent of account, FIX, token bytes and certificate serial. Phase 1 M2M/API clients confirmed. | Exact issuer mapping and principal lifecycle/non-reassignment procedure. |
| D2 | Separate authentication from business authorization; initial opaque Local Lab selected after owner confirms no approved IdP. | Exact lab credential lifecycle, later access-token profile, production transport, certificate lifecycle and binding implementation. |
| D3 | Explicit Query-local account grants; distinct approval/provisioning/enforcement responsibilities. | Named authority, source/precedence, snapshot convergence, emergency isolation and evidenced revocation targets. |
| D4 | Envoy Gateway target external boundary; restricted exposure and independent Query authorization. | Version/hostname/PKI, exact route support, rate limits, CNI and dependency networking. |
| D5 | Verifiable proxy-to-Query identity evidence and certificate/token consistency. | Selected evidence format, authorized Gateway identity, backend authentication and compatible IdP/controller capability. |

## OD-01–OD-19

OD-01–OD-15 retain the original numbering. OD-16–OD-19 are assigned in this revision to
previously unnumbered required decisions; the IDs do not imply their values were approved.

| ID | Decision | Status / resolution needed | Approval gate |
| --- | --- | --- | --- |
| OD-01 | Client population | Approved by user in Phase 0 round 1: M2M/API clients only; independently provisioned stable principal per client. Individual-user delegation is outside this scope. | Resolved for Phase 1 scope |
| OD-02 | Grant Approval Authority | Local Lab only: user approves test Principal/synthetic Account authorization matrix; authorized tooling provisions synthetic credentials. This is not permanent customer-grant governance. Production authority, Secret management and emergency responsibility remain independent decisions. | Five-principal synthetic matrix approved in principle; fixture/rotation evidence pending; production separately |
| OD-03 | Grant revocation target | Five-minute operational SLO candidate only; approve measurement origin and goal. No hard SLA without enforcement evidence. | Initial contract before Phase 1; production target after evidence |
| OD-04 | Emergency revocation | Required / unresolved: executable disable/isolation, completion proof and failure escalation. | Before Phase 1 |
| OD-05 | Authorization Server/profile | Q7: project owner confirms no approved usable IdP. Initial opaque Local Lab prerequisite satisfied; OAuth evaluation remains deferred. Provider/profile/binding capability remain unselected. | Initial direction resolved; implementation approval still required; OAuth separately |
| OD-06 | Certificate identity/PKI | Required / unresolved: profiles, roots, issuance, renewal/revocation, active-connection/resumption handling. | Before Phase 5 |
| OD-07 | Certificate/token binding | RFC 8705 preferred candidate; alternative requires equivalent explicit verifiable contract and approval. | Phase 5 entry spike |
| OD-08 | TLS termination/proxy evidence | XFCC SanitizeSet candidate; decide verifier location, trusted transfer/parsing, stripping and direct-path rejection. | Phase 5 entry spike |
| OD-09 | Backend TLS client identity | Authenticated HTTPS direction approved; Gateway-specific backend mTLS preferred when trusting metadata. Verify actual identity enforcement, not shared-CA membership alone. | Before Phase 5 |
| OD-10 | Market Reference disclosure | Q9 approves authenticated QueryPrincipal for current Lab route without Account Grants; no anonymous or external publication approval. Cache correctness remains separate. | Lab route resolved; external rule before release |
| OD-11 | Freshness visibility | Q9 approves internal route with explicit Query-local operations-read permission, independent of Account Grants/OAuth scope. Both certification and placement consumers need credentials. Embedded metadata disclosure remains OD-18 and a Lab clarification. | Lab route resolved; embedded-data decision before implementation |
| OD-12 | Rate limits | Required / unresolved: lab per-proxy vs global/per-client production budgets, trustworthy key and failure behavior. | Phase 3 / external release |
| OD-13 | Cache consistency | Strengthened requirements approved; maintenance vs generation, revisions, age/freshness and per-endpoint cache selection unresolved. Thirty seconds is candidate age budget, not SLA. | Phase 2B before 2C |
| OD-14 | Gateway deployment | Required / unresolved: pinned compatible releases, host, namespace, trust provisioning, route support and CNI enforcement. | Phase 3 lab; Phase 5 production |
| OD-15 | Opaque retirement | Explicit modes/no downgrade approved; coexistence, cutoff and retirement proof unresolved. | Phase 4 migration; mandatory final Phase 5 gate |
| OD-16 | Phase 1 transport | User selected isolated Local Security Lab with synthetic credentials. Later security phases must be verifiable in development with replaceable environment trust/identity configuration. Self-signed test certificates are a candidate, not a production transport waiver. | Lab scope resolved; listener/trust before staging/production use |
| OD-17 | Effective revocation and rollback | Selected Tsubmit/Tblock/Tdrained/Trevoked/Tresume, whole-Query Lab shutdown and bounded drain. Protected current version/digest is run-scoped and independent of image rollback. Initial 20/5/90/120-second budgets are accepted test parameters, not measured guarantees. | Runtime isolation/rollback evidence required; emergency immediate cutoff not established |
| OD-18 | External response schema | Q12 approves removal of full checkpoints from ordinary envelopes; full internal operations diagnostics remain. Owner confirms no formal publication commitments: revise existing v1 in place, without a legacy-field migration or automatic v2. Exact minimal DTO, old cache and repository consumer updates remain in the consolidated proposal. | Compatibility premise resolved; exact DTO in package; future external release separately gated |
| OD-19 | Account semantics and revision | Phase 1 protects existing reservation projection. Round 2 selects authoritative available trading capacity as the future domain priority; cash balance/settlement work is excluded. Reservation versus fill accounting, publication, revision and monotonicity contracts still need evidence/design. | Semantic scope and domain priority resolved; separate implementation approval before domain changes |

## Phase 0 decision round 1

Source refresh: `8362a165e9ce05dc91247b55c9587866840a32ce`, 2026-10-23. The worktree was clean
before this documentation-only round. No product changes, deployment or runtime tests are
authorized by this record. The user's round 1 answers below resolve only the stated scope
decisions; all other D1–D5 and OD-01–OD-19 approval boundaries remain in force.

### Source-verified prerequisites

- The [Query build](../../query-service/build.gradle.kts) has no HTTP Security or OAuth Resource
  Server dependency. The [read controller](../../query-service/src/main/java/com/simplematch/queryservice/web/QueryReadController.java)
  exposes five GET methods without a trusted client principal or account authorization. No
  issuer/JWKS, principal or grant configuration was found in inspected runtime/manifests. This
  does not establish whether an approved IdP exists outside this repository.
- [Runtime configuration](../../query-service/src/main/resources/application.yaml) already uses
  Spring Environment and Kubernetes configuration imports. It does not designate an identity
  policy source. The opt-in [rebuild controller](../../query-service/src/main/java/com/simplematch/queryservice/runtime/QueryProjectionRebuildController.java)
  uses its independent operator credential; that credential provides no ordinary read identity.
- The [certification collector](../../../scripts/end-to-end/query-service/lib/evidence.sh) makes
  unauthenticated loopback HTTP requests, selects a latest joined fixture and expects sensitive
  Redis keys. Phase 1 must provision explicit synthetic account grants and revise those key
  expectations. The [placement observer](../../../scripts/lib/local-java-placement-serving.sh)
  uses `/api/v1/freshness`; its operations access needs an approved policy or observer change.
  No production Java HTTP caller was identified in the scoped search; off-repository consumers
  remain unconfirmed.
- [Base manifests](../../../deploy/k8s/base/java-services.yaml) retain two Query replicas and an
  incoming HTTP listener on 8086 with HTTP probes. Committed FIX security and outbound Query
  Redis TLS/ACL do not provide Query incoming HTTPS or client authorization. No Gateway resources
  were found under the inspected deployment tree. These are source facts, not live evidence.

### Round 1 answers — user approved, 2026-10-23

The user explicitly accepted Q1–Q3 recommendations, with the Q2/Q3 qualifications below.
This approves planning scope, not implementation, runtime acceptance or a production release.

| Question | Decision | Approved scope | Downstream decisions |
| --- | --- | --- | --- |
| Q1 | OD-01 client population | M2M/API clients only; one stable independently provisioned Query principal per client. | End-user delegation, external identity mapping and credential lifecycle. |
| Q2 | Initial Phase 1 delivery environment / OD-16 | Isolated Local Security Lab with synthetic credentials and application-security evidence. | Initial auth mode, policy provisioning, transport scope and revocation evidence boundary. |
| Q3 | OD-19 bounded Account scope | Protect the existing reservation-related projection; authoritative balance/capacity aggregation and strict monotonic-read guarantees require separate scope. | Phase 1 response claims and any later Account semantic/revision redesign. |

Q2 qualification: later security phases must be reproducible in development without requiring
production certificate infrastructure. Keep trust material, expected identities, issuer/audience
and provisioning configuration replaceable through existing environment/overlay conventions.
Self-signed certificates are permitted for evaluation, not selected as a verified mechanism.
A local test CA is the recommended candidate for exercising chain, identity, rotation and
revocation behavior; it is still a small PKI. Explicitly trusted/pinned leaf certificates are an
alternative with different distribution/revocation costs. Neither permits trust-all TLS,
hostname-check bypass or removal of final certificate/token binding. Production integration
must rerun environment-specific trust, lifecycle and failure gates; replacing files alone is
not production acceptance.

Q3 qualification, narrowed by Q6: authoritative available trading capacity remains a required
future design objective; cash-balance engineering is excluded. It needs a separately approved Account/Risk
domain contract before Query publishes those meanings. The priority assessment lives in the
[Account semantics review](account-summary-semantics-review.md); no domain implementation,
schema/event change or new authority is authorized by the present answer.

At this historical round, the next frontier was approved IdP availability outside the repository
and initial authentication mode; named grant/credential/deployment responsibilities and one policy
authority; effective/in-flight/emergency/rollback contract; route/operations/Market Reference
policy and consumer fixtures. Numerical SLAs, PKI, cache generation, performance thresholds and
later-phase mechanisms remain unapproved. The full Phase 1 boundary must be reviewed before
ticket publication or implementation; that round created no tickets or implementation branch.
The later accepted publication outcome is recorded below.

### Round 2 answers — user approved scope, prerequisite outstanding

| Question | Decision | Approved scope / remaining boundary |
| --- | --- | --- |
| Q4 | Initial authentication | Security boundary first; OAuth evaluation deferred. Opaque Local Lab path is conditional on confirming no approved usable IdP. No outside-organization absence is inferred from repository configuration. |
| Q5 | Lab grant approval | User approves the test Principal/synthetic Account matrix only; authorized tools generate credentials. No permanent production/customer-grant governance is established. |
| Q6 | Domain priority | Available trading capacity first, with successful execution consuming capacity. Cash-balance engineering is excluded from this roadmap. Detailed reserve/fill/release transitions remain a separate domain contract. |

Lab credentials MUST be generated for tests, never hardcoded in source, committed, logged or
included in evidence. Nonsecret fixture identities and the approved grant matrix may be
versioned. Exercise rotation, disabled principal, revoked grant and cross-principal denial
through real HTTP tests. Runtime secret delivery/cleanup is part of the pending lab policy
contract. Production grant authority, Secret lifecycle and emergency revocation require their
own approval; a lab role decision does not satisfy them.

Q4's prerequisite was subsequently satisfied by the project owner's explicit Q7 confirmation
that no approved usable IdP exists; this is owner confirmation, not an inference from source.
If an approved usable IdP is discovered, reopen only the initial-mode decision; do not silently
expand Phase 1 into OAuth. Detailed policy lifecycle/revocation and consumer route decisions
remain required before Phase 1 execution approval. No implementation is authorized by this round.

### Round 3 answers — Q7–Q9 approved directions

| Question | Approved decision | Remaining boundary |
| --- | --- | --- |
| Q7 | Owner confirms no approved usable IdP; opaque Phase 1 prerequisite satisfied. | OAuth remains a later evaluation; no implementation or production release approval. |
| Q8 | Controlled stop/drain, V2 verification and resume; subsequently refined to record Trevoked separately before Tresume. Failed activation stays unavailable; rollback cannot restore grants. | Run-scoped enforcement and timestamp evidence; whole-process shutdown also pauses projection. No dynamic lease/online authorization requirement inferred. |
| Q9 | Account reads use grants/ownership; Market Reference requires QueryPrincipal; Freshness requires Query-local operations-read; minimal probes; other management default deny; rebuild uses independent operator credential. | Real filter-chain and consumer compatibility tests; exact route matching; embedded metadata disclosure decision. |

These are approved directions, not accepted runtime capabilities. The five-principal fixture
matrix is approved in principle. Concrete fixture/configuration details, the selected rotation
and request-lifetime controls, one Spring Environment policy source and enforceable anti-rollback
procedure remain subject to implementation review and acceptance; the revised ticket breakdown
has since been accepted for publication. Credentials generated
by the controlled Fixture Provisioning process before deployment are never part of a committed
fixture or policy example; Pods do not issue credentials independently.

### Feasibility review and remaining choices

- Block must cover new requests on existing keep-alive connections, Pod/ClusterIP and permitted
  port-forward paths, not just Service readiness. A rolling deployment or changed Secret is not
  proof that old policy is unable to serve. Verify actual runtime identity/version for every
  eligible replica and prevent later stale replicas joining after Resume.
- Q10 selects controlled full shutdown/restart for Local Lab; projection lag is acceptable;
  no consumer-offset reset or rebuild is required for a policy update. Application-only read
  gating can preserve consumers but adds gate activation/acknowledgement and maintenance control
  security. Application-only gating remains a later alternative. The base 30-second Pod grace is not an approved drain
  deadline or proof that shutdown completes within it.
- Anti-rollback needs a protected current approved policy version/digest within this disposable
  Lab run, independent of image rollback. A restricted deployment/resume procedure enforces the
  approved artifact or keeps reads unavailable; no enterprise or cross-Lab registry. A version field,
  per-process maximum or digest alone cannot establish authority. Old images lacking enforcement
  are not valid rollback targets. This procedural Lab candidate does not defeat an administrator
  who can replace both policy authority and enforcement.
- Q11 selects the in-flight rule: requests admitted before completed Block may finish within a separately
  approved finite deadline; then terminate remaining serving processes and verify isolation.
  Already-delivered bytes cannot be revoked. Emergency no-completion semantics would need a
  separate enforceable contract. Failure or unverifiable isolation keeps protected reads blocked.
- General QueryReadResponse envelopes currently contain full QueryFreshness, including topic,
  partition, offset and recovery state. Q9's endpoint permission alone does not conceal this
  metadata. Q12 selects removing full checkpoints from ordinary envelopes and retaining them on
  operations-read /freshness. The selected minimal v1 projectionState contract needs accurate
  snapshot semantics. All four routes, including Market Reference, bypass old cache JSON entirely.
- An operations-read-only principal also qualifies for Market Reference under the approved
  authenticated-client rule. It still has no Account/Order/Execution grants. The Market Reference
  wildcard in the discussion denotes a route family, not permission to expose unknown child paths.

Candidate tests and execution trade-offs are recorded in the implementation plan. No policy
authority storage, numeric deadline, new management endpoint or response-schema change is
approved by this feasibility assessment; the subsequently approved Q10–Q12 directions below
supersede only their corresponding previously pending choices.

### Round 4 answers — Q10–Q12 directions approved

| Question | Approved selection | Conditions and remaining evidence |
| --- | --- | --- |
| Q10 | Accept temporary whole-Query/Projection pause in Local Lab. | Preserve offsets/checkpoints, prove no skipped input and bounded catch-up; verify old replicas cannot serve. Not final production HA; a future read gate alone does not make HTTP reads highly available. |
| Q11 | Allow bounded drain of admitted requests, then terminate/isolate remaining old processes. | Define submission/Block/effective timestamps and budget. No new old-policy response may be generated after effectiveness. Separate routine updates from emergency cutoff; bytes sent before cutoff cannot be recalled. |
| Q12 | Remove full checkpoints from ordinary responses; keep operations diagnostics and approved minimal public freshness. | Owner subsequently confirms no formal publication commitments; revise v1 in place with repository consumers/cache. Exact DTO still needs review; do not invent timestamps/revisions. |

Compatibility confirmation: the project owner explicitly states that the repository has no
formal publication commitments. This resolves the commitment check for the Q12 change; it is
owner-supplied context, not a claim inferred from source or proof that no one has ever called
the API. No external legacy-field support window or v2 is required solely for this revision.
Known collectors, DTO tests, stored cache responses and certification still need coordinated
updates. Future public exposure and the remaining consolidated design proposals are not
approved by this confirmation.

There are no further numbered Phase 0 questions scheduled. Engineering details are proposed as
one [Phase 1 design review package](../../../docs/plans/query-security-cache-implementation.md#phase-1-consolidated-design-review-package).
The user accepted the revised seven-ticket scope, concrete fixture requirements and direct
blocking edges for publication. The five-principal matrix remains approved in principle.
Published issues are #188–#194, with all eight native blocking relationships and ready-for-agent
labels verified by read-back. No issue was assigned and no implementation was started. A label
or publication approval does not waive the separate implementation start gate. See the
[shared fixture contract in QS1-01 / #188](https://github.com/WenHsuanYu/SimpleMatch/issues/188); GitHub owns workflow state.

| Review ID | Published issue | Direct blockers |
| --- | --- | --- |
| QS1-01 | [#188](https://github.com/WenHsuanYu/SimpleMatch/issues/188) | None; implementation separately gated |
| QS1-02 | [#189](https://github.com/WenHsuanYu/SimpleMatch/issues/189) | #188 |
| QS1-03 | [#190](https://github.com/WenHsuanYu/SimpleMatch/issues/190) | #189 |
| QS1-04 | [#191](https://github.com/WenHsuanYu/SimpleMatch/issues/191) | #190 |
| QS1-05 | [#192](https://github.com/WenHsuanYu/SimpleMatch/issues/192) | #189 |
| QS1-06 | [#193](https://github.com/WenHsuanYu/SimpleMatch/issues/193) | #189 |
| QS1-07 | [#194](https://github.com/WenHsuanYu/SimpleMatch/issues/194) | #191, #192, #193 |


Implementation start update: the user subsequently authorized #188 / QS1-01 using implement +
tdd as a single slice. The implementation and its evidence are tracked in that issue and the
[opaque authentication note](opaque-authentication.md). #189–#194 and later phases are not started
by this authorization. Publication-time state above remains historical; GitHub owns current state.

The user's subsequent five-point simplification selects the following bounded refinements.
Production responsibility/SLA, Gateway/PKI/OAuth, Phase 2 consistency/performance and authoritative
trading-capacity publication remain separate; these selections do not authorize implementation.

### Consolidated Local Lab simplification — accepted design refinements

| Item | Recorded resolution | Evidence boundary |
| --- | --- | --- |
| Revocation | Tsubmit → Tblock → Tdrained → Trevoked → Tresume. Trevoked proves old policy cannot disclose; Tresume proves V2 verification and reopened reads. | State-transition correctness, no commercial SLA; failed V2 may leave revoked/unavailable state. |
| Verify isolation | Credential Generation 1 → Generation 2, verifier gets Generation 2 before collectors, zero overlap is acceptable; Principal IDs remain stable. Equivalent proven Lab gate/traffic isolation may replace this fallback. | Rotation is not mandatory for every policy change; prove old instances cannot serve regardless. |
| Anti-rollback | Protected current version/digest independent of image, retained throughout the same controlled run. | No central DB, enterprise approval or permanent cross-Lab retirement registry. Fresh version numbering only after complete teardown and new namespace/identities/data. |
| Envelope/cache | Revise v1 directly to data plus minimal freshness.projectionState; all four business routes PostgreSQL-only. | Preserve operations diagnostics and consistent snapshot semantics; no old cached JSON, dual DTO, v2 or deprecation window. Typed cache deferred. |
| Budgets/evidence | 20s drain, 5s statement, 90s stop verification, 120s catch-up as first test configuration. | Hypotheses only; focused affected tests during work and reproducible complete phase evidence at completion. |

This refinement supersedes the package's earlier mandatory all-credential rollover, broader
retirement-record proposal and tentative Market Reference typed-cache option. Principal identity
must still not be recycled within an active/persistent identity lifecycle; a completely destroyed
disposable run has no surviving identities to migrate. Do not treat an ordinary restart or a
lost approval record as fresh-run teardown.

### Fixture and ticket ownership refinement

The five-principal matrix is approved in principle. Controlled predeployment provisioning supplies
identical approved mappings/policy versions to all replicas; Query does not generate credentials.
QS1-01 owns loading/validation and QS1-06 owns deployed rotation/revocation proof. Use Credential
Generation 1/2 naming, independent of Client A/B. X/Y fixtures include Orders with/without
Executions and an absent parent; V2 removes only C→Y while preserving C→X and B→Y. Operator
credentials stay outside the principal matrix. No additional Principal is required.

QS1-01–06 own their behavior tests. QS1-07 integrates collectors, audits coverage and executes
cross-route/replica/lifecycle acceptance using those suites; missing owned evidence blocks it.
The implementation plan records the explicit supersession of closed #137's response-cache and
embedded-checkpoint expectations while retaining historical evidence. Closed #138 remains
platform context, not a parent. No historical issue is modified or reopened.

## Alternatives and recommendations, not additional approvals

| Choice | Recommended evaluation order | Trade-off / stop condition |
| --- | --- | --- |
| Opaque vs JWT first | Check approved IdP availability; opaque only when transition is justified. | Avoid disposable auth infrastructure, but do not block a bounded security repair on an unselected IdP. Changing phase scope needs approval. |
| Sensitive caches | Keep Phase 1 PostgreSQL-only; use Phase 2A measurements per endpoint. | Less complexity and no cache disclosure path; real DB load still must be measured. No assumption that every cache is beneficial. |
| Rebuild maintenance vs generation | Evaluate current controlled reset process plus required admission/drain/writer coordination first. | Maintenance sacrifices read availability; generation adds durable state and replica coordination. Replica count alone is not a fence. |
| Account cache revision | First choose data meaning and proven source/projection ordering, then comparable revision and atomic update design. | A Query-local counter can order applied snapshots but cannot manufacture business ordering. No account cache without evidence. |
| Client monotonicity | Test PostgreSQL-only sequential reads/replay first; evaluate minimum revision token only if strict guarantee needs it. | Tokens affect client contract, resets, epochs and unavailable responses; Redis CAS alone is insufficient. |
| Proxy evidence | Spike explicit Gateway backend identity plus sanitized evidence and token binding. | Backend mTLS preferred; unsupported controller or IdP means final gate blocked, not permission to trust headers. |
| Hard revocation | Measure initial controlled snapshot rollout/emergency isolation before selecting leases or online checks. | SLO observation is not a hard bound; availability trade-off and failure isolation must be approved. |

Approval records must identify approver, scope, selected values, prerequisite evidence and phase.
Evidence records must identify source, environment, exact command/scenario, outcome, artifacts,
restoration and limitations. Do not change an entry to Runtime accepted because a document or
manifest was reviewed. No deadlines, production secrets or responsible human identities are
invented by this register.

## Review Resolution Addendum A1–A7

Status: **Required Specification Amendments incorporated; implementation not authorized**.
The five engineering contracts are normative in
[specification section 12](security-external-access-spec.md#12-required-specification-amendments-a1a5).
A6 traceability and A7 planning/handoff requirements are implemented in the
[requirement-to-test matrix](../../../docs/plans/query-security-cache-implementation.md#11-addendum-requirement-to-test-traceability).
No test listed there has been executed for this documentation change.

### Precedence and unchanged clauses

The following preceding clauses remain in force, with no change to their approval boundaries:

| Previous clause | Unchanged requirement |
| --- | --- |
| D1–D5 | Stable principal; authentication distinct from account grants; Query-local policy; target Gateway boundary; verifiable proxy trust. No chosen IdP/PKI or forwarding mechanism. |
| R1 / OD-16 | Synthetic-credential lab application evidence separate from production verified transport. |
| R2 / Phase 1 | Original three-route cache non-use remains; subsequent user simplification additionally selects PostgreSQL-only Market Reference. |
| R3 / OD-06–09,14 | Binding/trust capability spike required; XFCC, backend mTLS details, PKI and versions remain conditional. |
| R4 / OD-02–04,17 | Explicit effective/in-flight/emergency/rollback contract; five minutes only candidate operational SLO, no hard SLA claim. |
| R5 / OD-13 | No cache-fill version regression; maintenance versus generation conditional; CAS not equivalent to client monotonicity; thirty seconds only candidate age budget. |
| R6 / OD-18 | Approve external freshness disclosure/schema; no automatic v2 for an unpublished API. |
| R7 / INV-10 | Gateway AND application allowlists; route attachment and additive NetworkPolicy enforcement. |
| R8 / OD-05,15 | Explicit auth modes, no JWT-failure fallback, no final opaque-only/JWT-only bypass. |
| Account finding / OD-19 | Reservation-event projection is not authoritative balance; Account Cache stays off without semantics/revision evidence. |
| Phase 0–6 / INV-01–10 / CON-01–07 | Phase 2A/2B/2C split and selective caches, independent phase gates, downstream isolation, no implementation/release approval from documents. |

### Amendment resolution and decision mapping

| Amendment | Required refinement | Existing invariant / decision mapping | Gate |
| --- | --- | --- | --- |
| A1 | Independent principal/grant/ownership/typed identity checks; entire mixed collection rejected; authorized durable fallback only; sanitized integrity failures. | INV-02/03/04/07; CON-01/02/07; D3; OD-13/19; R2/R5 | Phase 1 no-use boundary; Phase 2C validation before any protected cache re-enablement. |
| A2 | Separate new, established and resumed TLS behavior; all replicas; bounded rotation overlap and old token/certificate binding; backend identity rotation. | INV-06/07/09; D2/D5; OD-04/06/07/08/09/14; R3/R4 | Phase 5 pinned-runtime lifecycle evidence, extended in 6. |
| A3 | Non-recycled immutable principal, explicit lifecycle, one policy authority, schema/version/reference validation, coherent snapshots, effective adoption and anti-rollback. | INV-01/03/05/07; D1/D2/D3; OD-01/02/03/04/05/15/17; R4/R8 | Phase 1 lifecycle gate, extended in 4/5. |
| A4 | Route matrix, status/challenge/cache headers, no unintended authentication schemes, chain-specific CSRF/session review and real HTTP security tests. | INV-01–06/10; D2/D4; OD-10/11/12/15/16/18; R1/R2/R7/R8 | Phase 1 application gate; Gateway alignment in 3/5. |
| A5 | Comparable authorized baseline, named workload profiles/metrics/query budgets and independent correctness/performance gates; explicit per-endpoint outcome. | CON-01–07; INV-02/04/08; OD-13/19; R5 | Phase 2A baseline, 2B approved budgets/design, 2C both gates. |

OD-01–OD-19 retain their numbers. A4 establishes authenticated-client Market Reference as the
default application rule; OD-10 still owns external publication and detailed disclosure/cache
policy. A3 refines D3 responsibilities into Grant Approval Authority, Credential Provisioning
Operator, Deployment Operator and Enforcement Service; who holds each role remains unresolved.
A3 adds required rejection of regressing policy/schema versions and invalid principal references,
without selecting storage/lease/coordination technology. No SLO, numeric performance threshold,
PKI, generation or certificate-propagation candidate becomes approved by this amendment.

### Repository compatibility and interpretation

No conflict was found with the documented Query downstream ownership, Spring Environment,
Flyway or phase-based verification rules. There are implementation gaps, not permission to
weaken the amendments: current sensitive routes lack security, use eager PostgreSQL plus Redis,
and have controller-focused tests; certification expects sensitive Redis keys. Account events
lack a proven account revision. These are future scoped changes, not delivered capabilities.

Account Summary's account selector is checked against grants independently of cached content;
it does not require an invented Account Authority lookup. Existing-resource visibility remains
the approved consistency contract. Order/Executions require Query-owned parent ownership.
Principal disablement and grants-to-disabled rejection must be one coherent policy transition;
do not keep an invalid new snapshot and report revocation complete. 405/other routing errors
apply where appropriate after security handling, not as a bypass of authentication-first rules.
Internal-only does not mean anonymous or universally trusted cluster traffic: define consumers
and their operations policy. Market Reference and embedded freshness may change consumer access;
inventory and approve compatibility before implementation.

Completion of these document amendments restores planning requirements only. Every future phase
needs its own approved scope and actual evidence; no new service/database dependency or security
bypass is justified solely by implementation convenience.
