# Query Security and Cache Architecture Specification

Status: **Conditionally approved architecture; implementation approval pending.**
Reference and inspected HEAD: `9e68995233f49319974b76f826a59f88f14538cb`.
Revision basis: the Principal Engineer Review Resolution and its Required Specification
Amendments addendum supplied in this conversation. Section 12 incorporates A1–A5 as mandatory
contracts; the decision register records precedence and unchanged clauses.
This is the repository edition of the previously conversation-only specification; the
resolution supersedes conflicting details in that original proposal. This document is a
target contract, not a statement of implemented or certified capabilities.

Companion documents:

- [Decision register and R1–R8 resolution](security-decision-register.md).
- [Account semantics and revision review](account-summary-semantics-review.md).
- [Phased implementation and evidence plan](../../../docs/plans/query-security-cache-implementation.md).
- [Current Query overview](README.md), [domain ownership](../../../CONTEXT.md), and
  [current rebuild procedure](../platform/query-service-rebuild.md).

## 1. Scope, authority and evidence boundary

Only specification revision and implementation planning are authorized in this revision.
Do not modify product code, manifests, credentials, schemas or phase runners, deploy a lab,
or begin an implementation phase without its separate approval. No runtime test was run for
this document revision. Existing tests are source evidence, not newly passing evidence.
GitHub Issues remain the executable task tracker; no owning issue or original full Security
finding has been identified for this revision. Do not close a finding from this document alone.

MUST indicates a requirement, SHOULD a recommended default, MAY an optional capability.
An approved direction is not approval of a candidate mechanism, production SLO or release.
Use the repository development workflow, Spring Environment configuration authority, existing
Gradle conventions, Flyway migrations, Kustomize overlays and certification planner.

The inspected worktree contains pre-existing Query Redis TLS/ACL/namespace, FIX and deployment
changes. Preserve these; do not treat them as committed or deployed. The Query controller has
no general client authentication or account authorization. Its PostgreSQL arguments are
evaluated before Redis lookup. Query has a ClusterIP Service on 8086 and two replicas in the
reviewed overlays. No Gateway resources were found in the preceding four-overlay static
review. Spring Boot is 4.1.0; Query has no explicit Resource Server dependency. These baseline
facts must be rechecked before implementation; they do not establish live deployment state.

## 2. Architectural invariants

| ID | Required property |
| --- | --- |
| INV-01 | Every protected read establishes a trusted Query principal. |
| INV-02 | Account, Order and Execution reads are authorized using trusted resource account ownership. |
| INV-03 | Missing, invalid, disabled, conflicting or ambiguous required security inputs default to denial. |
| INV-04 | Redis cannot bypass authentication, grants, ownership or response identity validation. |
| INV-05 | FIX, operator credentials, network reachability and resource IDs imply no Query read privilege. |
| INV-06 | Direct Query access enforces the active mode, including binding evidence in final mode; only explicitly minimal health endpoints are exempt. |
| INV-07 | Once revocation is effective under the approved contract, caches, old instances or rollback cannot restore access. |
| INV-08 | Query security adds no synchronous dependency to matching, admission or other trading-critical execution. |
| INV-09 | Final external certificate and OAuth identity have a verifiable binding; independently valid unrelated credentials are rejected. |
| INV-10 | Only approved external host/method/path combinations are exposed; future routes and management paths default to denied. |

Query remains a downstream read model. It MUST NOT read another service's schema, obtain
account entitlements from Redis, or act as Account/Risk/Admission authority. Rebuild permission
remains separate from read permission. Local/test/staging/production share sensitive-route
authorization semantics; lab-only transport is an explicit evidence boundary, not an auth bypass.

## 3. D1 — Stable Query client principal

Approved in principle: an immutable Query-specific principal represents a provisioned API
client. M2M/institutional consumers remain an assumption awaiting OD-01 confirmation. One
institution may have multiple principals; one principal may have multiple grants; multiple
principals may read the same account. Machine identity does not establish individual-user
attribution. Account ID, FIX session, JWT bytes and certificate serial are not principal identity.

Verified credentials map to a registered principal, then server-owned account grants. Credential
rotation preserves that mapping. Principal identity must not be silently reassigned to another
client. OAuth `(issuer, subject)` is a candidate key, conditional on the issuer's stable identity
contract; a different approved client claim may be needed. Arbitrary claims/headers never
self-register principals or grant accounts.

## 4. D2 — Authentication modes and transport

Authentication establishes the verified caller. Authorization decides which account resources
that caller may read. A valid credential or OAuth scope alone does not confer account grants.
Spring Security owns the HTTP filter chain and security context. Query owns principal mapping
and account policy; do not build a custom OAuth server or JWT crypto framework.

### Initial mode and transport gate

Provisioned opaque bearer credentials are a conditional transition, not an unconditional
requirement. Before Phase 1, determine whether an approved IdP is already available. Direct JWT
adoption may avoid temporary credential infrastructure but requires explicit phase reapproval.
Otherwise use independently generated high-entropy credentials, protected provisioning, secure
verifiers/comparison, disabled-state checks and bounded approved rotation overlap. Never reuse
FIX passwords or rebuild tokens. Do not log tokens, secret policy material or authorization headers.

A controlled local security lab MAY use synthetic credentials over loopback/port-forward to
prove application security. The tunnel/HTTP arrangement is not evidence of production transport
security. Staging/production MUST use an approved encrypted, server-authenticated channel before
real bearer credentials are used. Native Query HTTPS is a candidate; Envoy/client mTLS need not
be introduced in Phase 1. An unprotected production listener carrying real credentials fails
the transport gate. No production disable-auth flag is allowed.

### OAuth mode

Use Client Credentials for approved M2M requirements; OIDC browser login is not implied. Prefer
a compatible Spring Security Resource Server and assess RFC 9068 as the access-token profile.
Require approved issuer, signature, algorithm allowlist, audience, expiration, applicable nbf,
stable registered identity, token type/purpose and profile-specific client claims. Reject ID
tokens, missing required claims, wrong-service tokens and ambiguous identity mappings.

Define bounded JWKS resolution/cache/rotation/outage behavior; do not follow attacker-controlled
key URLs or accept unverifiable tokens. Offline JWT validation does not provide immediate
revocation. JWT failure MUST NOT fall back to weaker opaque authentication. A coexistence plan,
if approved, must unambiguously select the verifier and document retirement. Phase 4 proves token
identity and grants, not external certificate binding.

### Final external mode

External mTLS and OAuth access tokens are both required. RFC 8705 certificate-bound tokens are
the preferred candidate, subject to IdP/controller capability verification. A JWT confirmation
thumbprint must be checked against trusted evidence of the external TLS peer certificate.
Unknown/untrusted/expired certificates, missing binding, mismatch, and unrelated client tokens
are rejected. Certificate issuance, trust roots, renewal, revocation, emergency disablement,
active connections and session resumption require explicit operational and convergence contracts.
Final external routes cannot retain opaque-only or JWT-only bypasses.

## 5. D3 — Query-local account grants and revocation

Approved in principle: validated, versioned, server-owned local policy snapshots mapping
`principalId -> Set<CanonicalAccountUUID>`. No wildcard, implicit administrator, FIX or operator
grants. Reject invalid/ambiguous mappings. Missing mandatory policy prevents protected reads.
Choose one configuration authority and explicit Spring Environment precedence; do not introduce
a second file-loader framework. Each request uses a consistent validated policy snapshot.

Separate Grant Approval Authority, Provisioning Operator and Query Enforcement responsibilities.
Kubernetes administration alone is not business approval to grant financial-account access.

Before Phase 1, define submission/start, effective revocation, maximum request lifetime and
whether in-flight requests may finish. Define an executable emergency disable/isolation
procedure, failed-rollout behavior and rollback protection. Secret updates, Ready status or a
rollout command alone do not prove enforcement convergence. Observe policy versions without
revealing grant contents, and verify old instances, direct paths and existing connections.
Never report effective revocation before its approved completion conditions are satisfied.

Five minutes is only an **operational SLO candidate**, not an approved SLA or hard guarantee.
Hard bounds require an evidenced mechanism such as a policy lease, mandatory version check or
reliable isolation of stale instances; none is selected here. Application rollback must not
reintroduce revoked policy. Grant, OAuth client/token and certificate revocation have distinct
responsible boundaries and must be tested separately.

## 6. D4 and D5 — External routing and proxy trust

Envoy Gateway is approved in principle as the target external boundary. PKI and Authorization
Server remain external responsibilities. Pin compatible Envoy Gateway and Gateway API versions
after capability verification; prefer standard GatewayClass/Gateway/HTTPRoute/BackendTLSPolicy,
using implementation-specific policies only where necessary.

Only approved hosts and methods for these routes may be externally published:

```text
GET /api/v1/accounts/{accountId}/summary
GET /api/v1/orders/{orderId}
GET /api/v1/orders/{orderId}/executions
```

These are application templates, not literal HTTPRoute variable syntax. Verify exact/prefix/
regex and method support, normalization, trailing/duplicate slashes, encoded separators,
HEAD/OPTIONS and unknown child paths. Limit allowedRoutes, namespace attachment and modification
RBAC. No broad `/api/v1/**` publication. Rebuild, Actuator and internal Freshness are not external
routes. Market Reference requires its own approved disclosure policy. Query also defaults
unknown/protected routes to denial independently of Gateway routing.

Production backend transport must authenticate Query's TLS server identity. If Query trusts
forwarded certificate metadata, it must also authenticate the metadata sender as an explicitly
authorized Gateway. Backend mTLS with Gateway-specific identity is the preferred candidate;
trusting every certificate under a shared CA is insufficient. XFCC SanitizeSet is a candidate,
not a capability already verified for the chosen controller. Strip spoofable external identity
headers, specify unambiguous metadata parsing and fail closed on missing/ambiguous evidence.
The backend peer certificate is Gateway identity, not the original external client certificate.

Before Phase 5, document who validates certificates/JWT/binding, the exact evidence transfer,
how direct ingress is rejected and how forged headers are defeated. Capability spike must prove
IdP binding plus Envoy forwarding plus Query backend identity verification. Without that proof,
do not claim RFC 8705 completion. No legacy direct path may bypass final binding requirements.

NetworkPolicies are additive: remove/narrow applicable broad allows, not just add a restrictive
policy. Verify namespaces/selectors, DNS/dependency egress, probes and actual CNI enforcement.
Control-plane/port-forward access needs separate RBAC. Rate limiting is separate from grants;
local buckets are per proxy, not cluster-global. Per-client limits require a trusted identity
key. Limits, distributed enforcement and failure behavior remain decisions, not defaults.

## 7. Phase 1 authorized PostgreSQL read contract

Account Summary, Order and Executions MUST bypass both Redis response reads and writes in Phase 1.
Old sensitive keys must never become an alternate response path. Physical cleanup of old keys
is separate from proving non-use; do not perform broad Redis deletion. Update certification
key-presence expectations explicitly. This is an approved temporary compatibility change.

Authenticate first. Account Summary compares the requested canonical account to active grants,
then reads Query PostgreSQL and validates returned identity. Order obtains trustworthy ownership
from Query's durable projection and checks that account grant; subsequent data reads are scoped
to authorized order/account identity. Executions first establishes parent existence/ownership,
then validates every returned execution's parent and account. Inconsistent durable data fails
closed; it is not silently filtered into apparent success. Do not assume order ownership is
immutable: the current upsert can overwrite account_id. Resolve/check the read race or enforce
an approved invariant before claiming correctness.

| Condition | HTTP contract |
| --- | --- |
| Missing/invalid required HTTP credential | 401, suitable authentication challenge, no resource disclosure |
| Authenticated unauthorized or absent resource | Same deliberate 404 existence semantics |
| Non-resource-specific permission denial | 403 where appropriate; never override resource-existence concealment |
| Authorized existing order without executions | 200 with empty collection |
| Invalid resource UUID after authentication | 400 |
| Unsupported method on an otherwise matched route | 405 where applicable after the applicable security checks |
| Rate limit exceeded | 429 under the approved Gateway contract |
| Required ownership/policy dependency unavailable | 503 or approved fail-closed unavailable response |
| Invalid mandatory policy | Reject activation/readiness or protected reads; no permissive fallback |

TLS rejection may occur before HTTP. Internal failure reasons remain observable without
leaking resource existence. Protected responses/errors use appropriate `Cache-Control: no-store`.
Minimal health routes must remain probe-compatible. Rebuild remains explicitly operator-token
authorized; adding Spring Security must neither bypass nor accidentally disable its protection.

Market Reference defaults to an explicit authenticated-client policy; public exposure requires
separate approval. It is not exempt from correctness: review cache identity, normalization,
replacement and consistency separately; no automatic account grants or public exposure.
Freshness stays internal by default. External payloads require an approved schema containing
only necessary freshness information; existing responses embed full checkpoints. An unpublished
external DTO does not automatically require v2. Existing consumers must be inventoried first.

## 8. Phase 2 consistency contracts

Account Summary is currently a last-applied reservation lifecycle projection, not demonstrated
account balance aggregation; see the [source review](account-summary-semantics-review.md).
It stays PostgreSQL-only until semantics, revision ordering and cache guarantees are verified.

| ID | Required consistency property |
| --- | --- |
| CON-01 | Post-rebuild requests cannot use pre-rebuild cache data. |
| CON-02 | A delayed cache fill cannot overwrite an already stored newer version of the same resource with an older one. |
| CON-03 | Account client-observed monotonic reads are an explicit design goal, not a claim derived from CAS; strict guarantees require a proven source/version/order contract. |
| CON-04 | Source-to-projection lag, cache snapshot age and reported freshness are separate quantities with approved budgets. |
| CON-05 | Payload, checkpoint and necessary generation have consistent PostgreSQL snapshot semantics, backed by atomic projection writes. |
| CON-06 | Only measured, correct caches are reintroduced; JDBC/Redis/workload evidence must improve on an acceptable PostgreSQL-only baseline. |
| CON-07 | Typed cache identity/collection validation, bounded failure and trusted Redis write boundaries remain required whenever a cache is enabled. |

Compare controlled maintenance first against durable generation fencing. Maintenance must stop
protected reads/fills, drain in-flight requests and coordinate all writers, including consumers
and reference installation, before reset/replay. Scaling to one replica alone does not prove
these properties. Define failure/resume boundaries. Generation, if selected, needs a trusted
authority, transactional reset transition and multi-replica visibility; old payloads cannot
receive a newly fetched generation during cache fill. Neither design is preselected.

For Account Cache, establish a trustworthy comparable per-account revision before atomic
conditional writes or an equivalent design. Timestamp, UUID event ID, reservation-local
version and offsets from different partitions are not proven per-account revisions. No
distributed lock/CAS platform is mandated. CAS prevents one overwrite race but not all observed
regressions: eviction/expiry, cache miss, in-flight responses, projection restore and cross-replica
reads need their own contract. Assess a client minimum revision/consistency token if strict
monotonic reads require one; define unavailability rather than returning below the required floor.

Thirty seconds is only a candidate cache-age budget. TTL from delayed insertion cannot prove
that age. Define the snapshot age origin, delayed-fill expiry, clock assumptions and maximum
request lifetime. Never advertise current Account/Matching authority state from this projection.

Payload/freshness/generation must use an approved consistent snapshot, e.g. short PostgreSQL
REPEATABLE READ for multiple SELECTs. Read-only transaction annotation alone is insufficient.
Do not wait for Redis while holding a PostgreSQL transaction/connection. Verify projection data,
inbox and checkpoint commit atomically: a correct read snapshot cannot repair incorrect writes.
Cached payload freshness must not be replaced with a newer checkpoint implying unapplied data.

## 9. Threat model and observability requirements

Phase 0 must approve the threat model, covering unauthenticated external callers, authenticated
cross-account callers, unauthorized cluster workloads, spoofed proxy metadata, stale policy
replicas and corrupted/misrouted cache data. Trust in the Authorization Server, PKI, provisioning
authority, Query-owned PostgreSQL and Redis writers must be explicit. A compromised authorized
cache writer is not defeated by JSON identity checks. Cluster-admin control-plane access and
grant business approval are separate responsibilities; neither is an implicit account privilege.

Record authentication/authorization rejection categories, effective policy version and rollout
state, JWKS/TLS failures, Gateway rate-limit events, ownership failures and rejected cache data.
When caches are enabled, measure hit/miss and DB work separately. Use approved principal and
correlation identifiers for audit without tokens, private keys, raw authorization headers or
entitlement registries. Avoid raw account/order/credential IDs as unbounded metric labels.
Define retention/access for audit evidence during operational review; do not invent values.
Internal diagnostics must distinguish concealed 404 denial from unavailable dependencies without
revealing unauthorized resource existence in HTTP responses. Secret rotation/revocation evidence
must remain redacted, including client scripts and TLS debugging output.

## 10. Delivery and release boundary

Phases are 0, 1, 2A baseline, 2B design, 2C selective caches, 3 Gateway lab, 4 OAuth, 5 final
mTLS/binding, 6 integrated certification. Phase 2 and 3/4 can be independently planned after
their prerequisites; do not start parallel implementation without authorization. Every phase
must pass its own security gates; Phase 6 is not deferred testing of earlier phases.

Each approved implementation handoff includes design, exact changed files, compatibility,
tests/commands/environment/source, failure/rollback, operational procedures and residual risks.
Use approved local deployment preflight and phase planner/reuse contracts before runtime work.
New acceptance semantics require aligned executable checks/fingerprints; old PASS reports do
not prove this security architecture. No production readiness from static manifests alone.

Final completion requires approved external routes with both verified certificate and OAuth
credentials, Query-enforced grants, no PostgreSQL/Redis/direct/legacy bypass, and reproducible
rotation/revocation/transport/cache/multi-replica evidence. Candidate SLOs and mechanisms are not
release criteria until approved and evidenced. Approval of this document is not implementation
or production release approval.

## 11. Authoritative reference entry points

Verify selected release compatibility before using examples; links are not capability evidence.

- [OWASP object authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)
  and [authorization guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html).
- [Spring Security architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
  and [JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
- [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.html),
  [RFC 8705](https://www.rfc-editor.org/rfc/rfc8705.html),
  [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html).
- [HTTPRoute](https://gateway-api.sigs.k8s.io/reference/api-types/httproute/),
  [external mTLS](https://gateway.envoyproxy.io/docs/tasks/security/mutual-tls/),
  [backend TLS](https://gateway.envoyproxy.io/docs/tasks/security/backend-tls/),
  [backend mTLS](https://gateway.envoyproxy.io/docs/tasks/security/backend-mtls/),
  [rate limiting](https://gateway.envoyproxy.io/docs/concepts/rate-limiting/),
  [NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/).

## 12. Required specification amendments A1–A5

These contracts supplement D1–D5 and R1–R8 without authorizing implementation. Their exact
phase/test mappings are in the [amendment traceability matrix](../../../docs/plans/query-security-cache-implementation.md#11-addendum-requirement-to-test-traceability).
The [resolution addendum](security-decision-register.md#review-resolution-addendum-a1a7)
identifies unchanged decisions, refinements and repository gaps. All completion gates below
require future evidence; incorporation into this specification is not runtime acceptance.

### A1 — Redis resource identity and authorization integrity

Authentication, effective grants, trusted ownership and typed cache identity are independent
checks. For an eligible protected cache path, establish the principal and grants, resolve trusted
resource ownership and authorize before cache access; then validate the complete typed response
envelope/schema and resource identity before disclosure. Account Summary uses the requested
canonical account as selector and independently checks its grant; cached data proves neither
authorization nor durable existence. Order/Executions require trusted parent ownership.

| Resource | Mandatory cached-response checks |
| --- | --- |
| Account Summary | Response canonical account equals requested AND authorized account; envelope/schema valid. |
| Order | Response order equals requested order and account equals the trusted ownership result. |
| Executions | Parent exists and is authorized; EVERY element has the requested order and authorized parent's account. Reject a mixed collection as a whole, not a filtered subset. |

Malformed JSON, incompatible schema or mismatched identity MUST NOT be returned. After successful
authorization/ownership, an invalid entry MAY be bypassed for a fresh scoped PostgreSQL read.
Failed/unavailable required ownership or authorization MUST NOT fall back to Redis. Record
sanitized integrity failures without foreign account identifiers in client errors. Suspected
broader integrity compromise should fail closed and be investigated, rather than be silently
hidden by fallback. Matching identifiers do not prove freshness/revision validity; version
correctness and authorization correctness must both pass. Same-cluster location is not trust.

Phase 1 satisfies the sensitive-cache boundary by using neither its reads nor writes. Phase 2C
may re-enable an endpoint only after A1 tests pass, including foreign accounts/orders, mixed
collections, invalid schema, missing/empty parent, ownership outage, warm-cache revocation,
obsolete generation and valid-identity/stale-revision data under the approved revision policy.

### A2 — Certificate revocation and connection lifecycle

Handshake validation and revocation on established connections are distinct controls. Before
final mTLS deployment, define issuer/revoker, trusted CAs, supported CRL or equivalent mechanism,
distribution and effective adoption on EVERY serving Envoy replica. Do not assume CRL updates
close existing connections or that a single replica's update establishes global effectiveness.

After effective revocation, new connections with the revoked certificate MUST be rejected.
Existing keep-alive connections need an explicit allow-until-boundary or reject policy; if
continued requests are forbidden, enforce termination, drain or request-level rejection.
Test session resumption under the pinned runtime configuration. If necessary validation cannot
be demonstrated, disable resumption on the affected listener or use another verified mitigation.
No immediate-revocation claim without connection-lifecycle evidence.

When certificate-bound tokens are selected, compare token thumbprint with trusted external-peer
evidence. Specify bounded old/new certificate overlap and behavior of existing tokens during
rotation. An old token cannot be accepted using a certificate that is no longer acceptable under
effective policy; replacement certificate plus old token must fail binding. Token expiration
alone does not satisfy emergency revocation. Token and certificate revocation remain separate.

Phase 5 evidence must include CA/revocation architecture, verified mechanism support, effective
configuration of all serving replicas, new/established/resumed connection tests, measured
propagation, rotation/binding tests, limitations and emergency procedure. Include backend Gateway
client-certificate rotation and rejection of unapproved Gateway identities. Fresh-handshake chain
validation alone cannot complete this gate; Phase 6 extends, not postpones, these tests.

### A3 — Principal, credential and policy lifecycle

Principal IDs are immutable, unique and never reassigned after retirement. Document suspension,
disablement, deletion, reactivation and retirement transitions. Disabled principals cannot
establish an authorized Query identity under effective policy. Rotation preserves identity;
new registration receives no automatically transferred grants. An issuer/subject change cannot
silently inherit grants; approved external mapping requires uniqueness/non-reuse and rejects
duplicates/conflicts. Old credentials cannot recreate a disabled principal or restore a grant.

Document credential issuance/distribution, verifier storage, rotation/overlap, expiry/disablement,
emergency revocation, audit and legacy retirement. Distinguish Grant Approval Authority,
Credential Provisioning Operator, Deployment Operator and Query Enforcement Service. Kubernetes
permission is not business account-access approval.

Designate one authoritative security policy source per environment for principal registration/
status, credential mappings, grants, policy version and revocation. Spring Environment remains
runtime resolution authority; Secrets should hold sensitive verification material. Document
ownership and precedence across Secrets, ConfigMaps, environment variables and any approved
registry; no silent overrides or independent configuration framework.

Before activation validate duplicate IDs/mappings, canonical accounts, required fields, supported
policy schema, verifier configuration, grants referencing unknown/disabled/retired principals and
invalid/regressing policy versions. Authentication and authorization use compatible coherent
immutable snapshots. Invalid activation fails closed, never defaults open. The lifecycle design
must specify atomic removal/deactivation of grants when disabling a principal so these rules do
not conflict. A rejected update must not be mistaken for completed revocation.

Specify how each replica obtains, validates, activates and reports policy, and when it stops
protected serving if its lifecycle contract cannot be met. Secret environment updates require
container replacement to observe changes; volume propagation and application reload must be
verified separately. No Secret-update-equals-adoption assumption. Independently control application
and policy rollback so old bundles cannot restore grants. Prove version non-regression across
restart/rollback using the approved authority mechanism; a version label alone is not enforcement.

### A4 — HTTP security and response contract

The actual version-compatible Spring Security chain authenticates before protected controller
operations and establishes QueryPrincipal. No unintended anonymous, Basic, Form Login or session
fallback. Direct Query access follows the same rules as Gateway-routed requests.

| Route | Default application access rule |
| --- | --- |
| GET account summary | Authenticated principal plus account grant. |
| GET order | Authentication, trusted order ownership and account grant. |
| GET order executions | Authentication, existing parent ownership and account grant. |
| GET market-reference | Explicit authenticated-client policy; public exposure separately approved. |
| GET freshness | Internal-only operations policy, not automatic permission from any network location. |
| POST internal/query/rebuild | Separate operator authentication and controlled deployment. |
| Actuator liveness/readiness | Minimal health for approved infrastructure. |
| Other Actuator routes | Explicit internal operations policy. |
| Unknown routes/methods | Default denial or appropriate routing error; test generated HEAD/OPTIONS behavior. |

Section 7 defines status semantics. OAuth uses RFC 6750-compatible WWW-Authenticate: Bearer
challenge for absent credentials and appropriate invalid_token for invalid access tokens.
Account-specific denial preserves concealed 404 semantics without sensitive error descriptions.
The initial provisioned opaque scheme documents HTTP Bearer use without claiming OAuth issuance.
Do not expose tokens, verifier internals or confidential grants in challenges/errors.

Sensitive Account/Order/Executions responses use no-store by default; internal Redis is a separate
cache contract. Document ambient cookie credentials, if any. Stateless explicit-bearer chains
MAY disable CSRF when justified; do not globally disable CSRF without reviewing every chain.
Review the state-changing rebuild route separately and test its explicit operator credential/
CSRF handling. Do not accidentally add session authentication.

Phase 1 requires compatible Spring Security MockMvc/real filter-chain integration (entry points,
exceptions and route rules) plus PostgreSQL integration, not controller-only tests. Test credential
and principal failures, spoofed headers, cross-account reads, authentication-before-UUID parsing,
methods/routes, rebuild separation, challenges, cache headers and required dependency failure.

### A5 — Performance baseline and selective cache acceptance

Measure authorized PostgreSQL-only first using the same grants, ownership, consistency, freshness,
fixtures and response contract as the cache comparison. Assess Account, Order, Executions and
Market Reference separately if proposed for caching. Numerical thresholds are proposed after
baseline measurement and approved before Phase 2C, not invented ahead of measurements.

Required measurements: JDBC counts for ownership/payload/freshness/generation/revision and total;
rows, query latency and plans when needed; Redis GET/SET, hit/miss, invalid entry and timeout;
p50/p95 and preferably p99 end-to-end latency; DB CPU/I/O/connection utilization; concurrency,
request rate, dataset and hot-key distribution; cache enabled state, TTL, replicas and pools.
Report hits and misses separately; aggregate means are insufficient. Count metadata and write
overhead; a smaller query plus Redis round trip is not automatically cheaper than an indexed read.

Before implementation approve per-endpoint hit/miss JDBC budgets. Exercise baseline, cold/warm
cache, same-resource concurrency, different-account/order concurrency, Redis timeout/outage,
PostgreSQL outage, rebuild/generation transition and multi-replica behavior when making cluster
claims. Correctness and performance are independent gates; both must pass for an enabled cache.

Define comparable per-resource revisions and atomic conditional writes or an equivalent verified
mechanism where stale-fill regression must be prevented. Do not use distributed locks solely to
suppress harmless duplicate misses. Account revision/source ordering precedes Account Cache;
cache no-regression is distinct from client monotonic reads. End Phase 2 with one recorded outcome
per endpoint: enabled with both gates proven; PostgreSQL-only retained due to cost/complexity;
or deferred with named missing requirement/evidence. Reintroducing Redis is not a completion goal.
