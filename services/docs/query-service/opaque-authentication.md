# Query opaque authentication — QS1-01

The subsequent [QS1-02 Account Summary slice](account-read-authorization.md) enables explicit
Account Grants and supersedes this first-slice note's Account-route denial. The #188 verification
record below retains its original scope and source identity.

Scope: [#188](https://github.com/WenHsuanYu/SimpleMatch/issues/188), authorized for one
implementation slice. This note records the accepted public test seams and implementation
choices; runtime outcomes are recorded separately. Later business authorization slices remain
blocked by their own acceptance criteria. OAuth, Gateway, PKI, production rollout and policy
reload/revocation orchestration are outside this change.

## Acceptance and public test seams

- Exercise the real HTTP SecurityFilterChain and application controllers with Query-owned
  PostgreSQL projections, using the existing runner-owned database/Flyway fixture contract.
- Missing, malformed, unknown, expired, disabled or ambiguous credentials return 401 with a
  Bearer challenge. Caller-controlled identity headers, cookies, FIX and operator credentials
  cannot establish a QueryPrincipal. Successful identity retains one immutable policy snapshot.
- An active Operations principal can read full internal freshness without account grants;
  another authenticated principal receives 403. Nonsecret active policy version is observable.
- Minimal liveness/readiness permit infrastructure probes. All unconverted business routes,
  other management endpoints, unknown routes and unapproved methods deny by default.
- The exact opt-in POST rebuild route retains its independent operator header check. A valid
  operator-only request works; Query credentials alone cannot stop consumers or reset state.
- Security is stateless, with no Basic/Form/session login fallback. Sensitive responses include
  no-store. Required policy absence or invalid registration/mapping/grants fails startup.
- Test the fixture provisioner's public CLI: provision private synthetic credentials before
  deployment, give every replica the same approved mapping/version, and never print/commit secrets.

## Configuration and identity contract

Spring Environment remains the single resolution authority. The dedicated immutable
`simplematch.query-service.security` properties use schema-version 1 and a positive policy-version.
A Kubernetes Lab publishes the complete policy through the existing Query Secret import; test/CLI
configuration uses the same property names. Do not split competing security keys across ConfigMap
and Secret or enable runtime refresh. Changes require a controlled process restart; adoption is
not proven merely by updating a Secret. Run-scoped anti-rollback and convergence belong to #193.

Principal entries have an immutable ID, ACTIVE/DISABLED/RETIRED status, explicit canonical account
UUID grants and operations-read. Credential entries have an independent ID, registered principal
ID, enabled flag, expiration instant and lowercase SHA-256 verifier. Duplicate identities/verifiers,
invalid grants and unsupported/missing policy data are rejected before serving. Inactive principals
cannot retain active grants or authenticate. An account UUID never establishes identity.

Opaque credentials are 32 CSPRNG bytes encoded as 43 unpadded Base64URL characters. Only the
verifier is delivered to Query. Fixed-size digests use constant-time comparison. Each request
uses one fixed policy snapshot; rotation can map replacement credentials to the same principal.
Expiration bounds any explicitly provisioned overlap. No legacy credential fallback exists.

Spring Security owns authentication context and HTTP authorization. Project code validates
provisioned policy and opaque credentials. This is the HTTP Bearer scheme, not an OAuth-issued
access token or an Authorization Server. Future authentication methods must preserve principal
and explicit-grant semantics.

## HTTP and failure contract

Only GET /api/v1/freshness with operations-read, GET of minimal liveness/readiness, and the exact
operator-authenticated POST /internal/query/rebuild are usable in this first slice. Authentication
failure returns 401; authenticated route/permission denial returns 403. Business-resource 404
concealment and malformed-UUID 400 behavior arrive with the later authorized business slices.
No HEAD/OPTIONS or broad route-family permit rule silently enables a future endpoint.
Tomcat's internal ERROR dispatch may finish the error response without a second authentication
challenge. External requests to /error remain denied; client headers cannot select a Servlet
dispatcher type. Authenticated permission denials set 403 directly instead of dispatching again.

The single stateless chain has no ambient cookie credentials or browser login. Its only accepted
state-changing route is rebuild, requiring the explicit operator header independently of Query
Bearer authentication. CSRF handling is chosen for those reviewed header-only interactions;
unknown state-changing routes remain denied. Health endpoints disclose no operational details.
Policy/dependency failures return no protected data; Redis is never involved in this slice's
Freshness authorization or durable read. Cache and business response changes belong to later tickets.

## Verification and delivery boundary

Run focused red/green HTTP tests, policy/fixture tests, the complete Query service suite, the
expanded existing PostgreSQL task and Java staticAnalysis. Preserve red and final evidence and
review the final diff against the pinned starting revision. No Kubernetes, TLS/ACL or multi-replica
claim follows from these tests. Keep pre-existing documentation work separate when staging.

## Provisioning and focused checks

Create a new bundle outside the repository and keep it private:

```bash
query_fixture_root="$(mktemp -d)"
python3 scripts/end-to-end/query-service/provision-security-fixtures.py \
  "$query_fixture_root/bundle" --policy-version 1
```

The new directory is 0700 and its files are 0600. application.properties contains only verifier
policy; credentials.json contains private client material and fixture-identities.json records the
synthetic ID mapping. Supply the complete properties through the existing Query Secret import
or, for a loopback Local Lab process with its existing database configuration/migrations, Spring
Config Data's additional-location pointing to this private file. Do not replace the existing
Secret's database/Kafka material, print the bundle, put tokens in arguments/URLs, or commit it.
The CLI creates a fresh disposable run, not a rotation of persistent Principals. All replicas
must receive the same approved policy file. The one-hour synthetic credential lifetime is a Lab
test choice; it is not a production rotation/SLA contract. Real credentials require the separate
approved transport gate. Query exposes no HTTP credential/registration API.

```bash
python3 scripts/end-to-end/query-service/tests/security-fixtures-contract.py
./gradlew -q :services:query-service:test
./gradlew -q :services:query-service:postgresJdbcTest
./gradlew -q staticAnalysis
```

The PostgreSQL task requires Docker, Python 3, and `psql`.
Testcontainers creates an isolated database and removes its container after the test JVM exits.
The fixture sets an ownership marker before Flyway migrations or fixture writes.
The task includes JDBC, real FilterChain, loopback HTTP, and the existing Python collector contracts.

The ordinary suite uses isolated migrated H2 fixtures. It does not establish PostgreSQL behavior.
Real loopback HTTP tests require local socket permission.
Run the PostgreSQL task directly for this fixture. The multi-service Flyway/reset runner uses a separate database.
Security fixture CLI checks also run in the existing local-resource lifecycle CI workflow.

Known compatibility impact: anonymous Freshness collectors now need an operations-read identity.
Account/Order/Execution/Market collectors remain blocked until their dedicated slices and #194
integrate them. Standalone controller/cache tests retain their narrow purpose and do not prove
HTTP authorization. Previously retained #137 evidence keeps its original revision and meaning.

The worktree started with four previously edited architecture documents and a published-ticket
packet. They remain separate from this slice's source commits. Runtime outcomes, final source
identity, review and cleanup are recorded in the #188 handoff rather than inferred from this note.

## Observed local verification at the #188 handoff

| Check | Result | Limit |
| --- | --- | --- |
| Complete Query test task | 194 passed, no failures/skips | Migrated H2 plus real FilterChain and loopback HTTP; not a deployment run |
| Expanded PostgreSQL task | 63 passed, no failures/skips | Owned native PostgreSQL 18 scratch database; includes JDBC and HTTP suites |
| Fixture provisioner CLI | 6 passed | Synthetic five-principal private bundles only |
| Root staticAnalysis | Passed | Existing rules unchanged; no new suppressions |
| Standards / Spec review | 0 actionable findings on each axis, including final deltas | Source review, not additional runtime certification |

The actual HTTP regression reproduced disabled rebuild returning401 instead of404 after Tomcat
ERROR dispatch; its corrected tests passed. The initial PostgreSQL integration run exposed HTTP
fixture checkpoints leaking into subsequent JDBC tests; after-test cleanup fixed isolation, and
both complete tasks passed again. Earlier failures remain retained as failures. Projection gap
fixture offset17 correctly reports last-processed16; only the mistaken test expectation changed.
The unavailable-read test injects a datasource connection failure; it does not claim a PostgreSQL
server outage/restart scenario. Existing unrelated unchecked mock warnings and the build tool's
Lombok/Unsafe notice remain outside this slice; no new Java warning was suppressed.

Framework implementation follows [Spring Security servlet architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
and the [pinned 7.1.0 AuthenticationFilter](https://github.com/spring-projects/spring-security/blob/7.1.0/web/src/main/java/org/springframework/security/web/authentication/AuthenticationFilter.java),
using the repository's Boot-managed dependencies. OAuth access-token capabilities are not implied.
