# Query Security Decision Register

Status: **Directions conditionally approved; mechanisms and runtime acceptance remain pending.**
Authority: the user-supplied Principal Engineer Review Resolution and A1–A7 addendum.
Scope is documentation only; the addendum below supplements rather than replaces R1–R8.
See the [revised specification](security-external-access-spec.md) and
[implementation/evidence plan](../../../docs/plans/query-security-cache-implementation.md).

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
| R2 Cache disclosure | Accepted, simplified. Account/Order/Executions use authorized PostgreSQL only; bypass both cache reads and writes. Review Market Reference independently and revise key-presence certification. | Phase 1 real HTTP and PostgreSQL gate; old sensitive Redis entries cannot serve responses. |
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
| D1 | Stable Query client principal independent of account, FIX, token bytes and certificate serial. | Initial client population, exact issuer mapping and principal lifecycle/non-reassignment procedure. |
| D2 | Separate authentication from business authorization; framework HTTP context and explicit authentication modes. | IdP availability vs opaque transition, access-token profile, transport, credential/certificate lifecycle and binding implementation. |
| D3 | Explicit Query-local account grants; distinct approval/provisioning/enforcement responsibilities. | Named authority, source/precedence, snapshot convergence, emergency isolation and evidenced revocation targets. |
| D4 | Envoy Gateway target external boundary; restricted exposure and independent Query authorization. | Version/hostname/PKI, exact route support, rate limits, CNI and dependency networking. |
| D5 | Verifiable proxy-to-Query identity evidence and certificate/token consistency. | Selected evidence format, authorized Gateway identity, backend authentication and compatible IdP/controller capability. |

## OD-01–OD-19

OD-01–OD-15 retain the original numbering. OD-16–OD-19 are assigned in this revision to
previously unnumbered required decisions; the IDs do not imply their values were approved.

| ID | Decision | Status / resolution needed | Approval gate |
| --- | --- | --- | --- |
| OD-01 | Client population | M2M/API client is an assumption; confirm or define user-delegation requirements. | Before Phase 1 |
| OD-02 | Grant Approval Authority | Required / unresolved: name business/security authority and approval procedure; distinct provisioning operator. | Before Phase 1 |
| OD-03 | Grant revocation target | Five-minute operational SLO candidate only; approve measurement origin and goal. No hard SLA without enforcement evidence. | Initial contract before Phase 1; production target after evidence |
| OD-04 | Emergency revocation | Required / unresolved: executable disable/isolation, completion proof and failure escalation. | Before Phase 1 |
| OD-05 | Authorization Server/profile | Required / unresolved: provider, issuer, identity guarantees, RFC 9068 suitability and RFC 8705 support. First establish availability before choosing opaque. | Phase 1 mode decision; full integration before Phase 4 |
| OD-06 | Certificate identity/PKI | Required / unresolved: profiles, roots, issuance, renewal/revocation, active-connection/resumption handling. | Before Phase 5 |
| OD-07 | Certificate/token binding | RFC 8705 preferred candidate; alternative requires equivalent explicit verifiable contract and approval. | Phase 5 entry spike |
| OD-08 | TLS termination/proxy evidence | XFCC SanitizeSet candidate; decide verifier location, trusted transfer/parsing, stripping and direct-path rejection. | Phase 5 entry spike |
| OD-09 | Backend TLS client identity | Authenticated HTTPS direction approved; Gateway-specific backend mTLS preferred when trusting metadata. Verify actual identity enforcement, not shared-CA membership alone. | Before Phase 5 |
| OD-10 | Market Reference disclosure | Independent policy required; no external publication until approved. Review cache identity/consistency separately. | Internal rule before Phase 1; external rule before release |
| OD-11 | Freshness visibility | Internal-only route direction approved; identify internal consumers. External embedded-field schema is OD-18. | Before Phase 1 route integration |
| OD-12 | Rate limits | Required / unresolved: lab per-proxy vs global/per-client production budgets, trustworthy key and failure behavior. | Phase 3 / external release |
| OD-13 | Cache consistency | Strengthened requirements approved; maintenance vs generation, revisions, age/freshness and per-endpoint cache selection unresolved. Thirty seconds is candidate age budget, not SLA. | Phase 2B before 2C |
| OD-14 | Gateway deployment | Required / unresolved: pinned compatible releases, host, namespace, trust provisioning, route support and CNI enforcement. | Phase 3 lab; Phase 5 production |
| OD-15 | Opaque retirement | Explicit modes/no downgrade approved; coexistence, cutoff and retirement proof unresolved. | Phase 4 migration; mandatory final Phase 5 gate |
| OD-16 | Phase 1 transport | Lab/production split approved; native Query HTTPS candidate. Decide listener, trust distribution and secure consumer configuration. | Before staging/production Phase 1 use |
| OD-17 | Effective revocation and rollback | Definitions required: request snapshot, in-flight completion/max lifetime, stale-instance isolation, failure and anti-rollback. No mechanism selected. | Before Phase 1 |
| OD-18 | External response schema | Minimal useful freshness direction approved; existing consumer inventory, exact fields and versioning necessity unresolved. | Before external publication |
| OD-19 | Account semantics and revision | Source review confirms reservation-local payload, account-key overwrite and absent account revision; product meaning/order/version/monotonicity unresolved. No balance/authority claim. | Semantic scope before Phase 1; revision proof before Account Cache/external monotonic guarantee |

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
| R2 / Phase 1 | Account/Order/Executions bypass BOTH Redis response reads and writes. Market Reference reviewed independently. |
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
