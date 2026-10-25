# Client and Account Onboarding

Status: **Q1–Q10 target contracts approved after review; implementation separately gated**.
Source baseline: `d50fb840578c72ed86745de79ce72da756178618`.

This design work follows the owner's clarification during
[QS1-07 / #194](https://github.com/WenHsuanYu/SimpleMatch/issues/194). It is a separate
domain/provisioning scope, not an implicit extension of Query certification or its approved
five-principal Lab policy. #194's complete deployed acceptance remains incomplete.

## Confirmed requirements

- Represent different business Clients and their respective Accounts. Here, business Client
  means the institution/customer relationship being modeled, not a credential or an API process.
- Accounts must be usable in a complete trading environment, rather than exist only as synthetic
  Query read fixtures. Cover Account authority, Risk admission, FIX permissions and Query access.
- Design the customer/account opening workflow for production as well as development/test.
  Approval to design production onboarding does not approve a production rollout, default test
  credentials, actual financial limits or automatic creation of production customers.
- Preserve the owner's available-trading-capacity objective. Cash-balance engineering is excluded.
- Do not write to databases while the model and execution contract are being clarified.

## Approved vocabulary

- A **business Client** is an organization with its own trading Accounts. It is distinct from
  an API process, FIX session or credential.
- An **Account** belongs to one business Client. A Client may own multiple Accounts.
- An **API Client** is a program making requests. Its registered Query identity is a
  **QueryPrincipal**; that identity does not itself establish business ownership.
- **Account Grants** and FIX Account allowlists specify access by a program/session. Membership
  in the owning Client does not automatically confer these permissions. Any delegated access
  requires explicit authorization; no shared ownership or administrator privilege is implied.

One approved onboarding workflow may configure ownership, trading authority and access together.
Keeping those facts distinct does not require three independent administrative systems.

## Existing contracts and verified gaps

| Area | Verified source contract | Gap or consequence |
| --- | --- | --- |
| Domain ownership | [Account aggregate ADR](../adr/0003-separate-account-authority-aggregate-roots.md) separates Reservation, limit and position roots; [Risk ADR](../adr/0004-risk-admission-aggregate-root.md) preserves Account RPC outside Risk transactions. | Do not introduce a second Risk account ledger or collapse Account state into a giant Client aggregate. |
| Daily notional | [AccountLimitLedger](../../services/account-service/src/main/java/com/simplematch/accountservice/authority/AccountLimitLedger.java) enforces total, reserved, utilized and available authority. [V10](../../services/account-service/src/main/resources/db/migration/account-service/V10__enforce_account_authority_contract.sql) establishes Account/trading-day uniqueness. | Initialization must respect the business day and must not reset existing reserved/utilized amounts. |
| Provisioning | [AccountReservationApplicationService](../../services/account-service/src/main/java/com/simplematch/accountservice/reservation/AccountReservationApplicationService.java) exposes transactional limit/empty-position provisioning. [JdbcAccountAuthorityLifecycleWriter](../../services/account-service/src/main/java/com/simplematch/accountservice/store/JdbcAccountAuthorityLifecycleWriter.java) uses plain INSERT. | Existing methods are not a repeatable administration workflow. All observed callers are tests; retry/conflict behavior needs an approved contract. |
| External entry | [Account v2 RPC](../../proto/account_v2.proto) currently exposes Reserve. | No verified customer/account-opening administration API or startup caller exists. The owner selected a future controlled CLI/Job. |
| Actual trading | [AccountAuthorityTransitions](../../services/account-service/src/main/java/com/simplematch/accountservice/reservation/AccountAuthorityTransitions.java) reserves daily available notional for BUY and available long inventory for SELL; BUY fills also require a position row. | A limit alone is insufficient for complete fill application. Empty positions enable neither arbitrary SELL nor invented holdings. |
| Risk authority | [OrderAdmissionApplicationService](../../services/risk-service/src/main/java/com/simplematch/riskservice/admission/OrderAdmissionApplicationService.java) calls Account reservation, and [GrpcAccountReservationClient](../../services/risk-service/src/main/java/com/simplematch/riskservice/admission/GrpcAccountReservationClient.java) carries canonical account/instrument/day facts. | Provision the Account-owned authority that Risk consumes; no independent per-account Risk notional ledger was found in the active path. |
| Business ownership | [AccountLimitIdentity](../../services/account-service/src/main/java/com/simplematch/accountservice/authority/AccountLimitIdentity.java) contains Account, trading day and currency. Account/Query/Risk main sources and migrations have no business Client ownership field. | Account-to-Client ownership is an additional domain contract. Query grants cannot stand in for it. |
| Query identity | [QueryPrincipal](../../services/query-service/src/main/java/com/simplematch/queryservice/security/QueryPrincipal.java) represents an authenticated API caller with explicit account grants and operations permission. | Preserve business ownership, authentication and access authorization as separate facts. |
| Query visibility | Provisioning methods insert Account authority without emitting Account lifecycle events. Query summaries are downstream reservation lifecycle projections. | An opened Account may never have a summary until reservation activity supplies an input event; this is not merely projection lag. |
| Existing seed | [Disposable SQL fixture](../../scripts/end-to-end/critical-consumers/sql/account-limit-fixture.sql), [seed helpers](../../scripts/end-to-end/critical-consumers/lib/cluster-data.sh), and [execution fixture](../../scripts/end-to-end/query-service/lib/execution-fixture.sh) use test-only values and newly generated IDs. | These are not production onboarding and must not become an unconditional startup reset. |
| FIX identity | [FixCounterparty](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/fix/FixCounterparty.java) and [FixSessionAccess](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/fix/FixSessionAccess.java) bind credentials and explicit Account allowlists to complete FIX SessionIDs. | A business Client is not an existing SessionID record. Separate client sessions are needed to demonstrate isolation. |
| FIX transport | [QuickFixAcceptorLifecycle](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/fix/QuickFixAcceptorLifecycle.java) requires TLS/client authentication and readable key/trust stores; [Gateway StatefulSet](../../deploy/k8s/quickfix-gateway-statefulset.yaml) mounts a nonoptional TLS Secret. | Account onboarding cannot bypass mTLS or substitute network reachability for authentication. |
| Cancellation | [OrderSessionRegistry](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/fix/OrderSessionRegistry.java) and [CancelOrderFixMessageHandler](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/fix/CancelOrderFixMessageHandler.java) enforce originating-session ownership. | Shared business Client membership or Account access must not implicitly authorize another session's cancellation. |
| Full environment bootstrap | [Kubernetes inputs](../../scripts/lib/local-certification-kubernetes.sh) create DB service Secrets but do not themselves provision FIX counterparties/TLS material; [phase graph](../../scripts/lib/local-certification-phase-graph.sh) declares migrations, workloads and explicit opening dependencies. | No automatic end-to-end Client/Account bootstrap was verified. A new provisioning stage needs declared inputs/dependencies and immutable evidence. |
| Trading open | [GatewayOperationalController](../../services/quickfix-gateway/src/main/java/com/simplematch/quickfixgateway/operations/GatewayOperationalController.java) requires explicit operational opening and fresh eligible observations. | A provisioned Account is not permission to open the exchange/session; retain independent operator trust. |

The source audit is complete for this decision round. No runtime execution or production-readiness
claim follows from the inspection. The two factual audits made no source/database/deployment changes.

## Decision register

| ID | Decision | Status |
| --- | --- | --- |
| CA-Q1 | Include complete development/test environments and production onboarding design. | Owner confirmed. Production deployment and operational policy remain separately gated. |
| CA-Q2 | Each Account belongs to one business Client; a Client may have multiple Accounts. Access grants are separate; delegated access requires explicit approval. | Owner approved. No ownership schema or access-policy change implemented. |
| CA-Q3 | First administration entry is a controlled CLI/Job executed after manual approval. | Owner approved direction. Approval roles, credentials, audit/retry and deployment contracts remain undecided. |
| CA-Q4 | Complete #194 first; separately plan and approve the Client/Account onboarding implementation. | Owner approved. Resume #194 without adding the new domain scope. |
| CA-Q5 | Preserve Client/Account IDs across restarts, image updates and trading days. A destroyed, independently recreated test environment may use new identities. | Owner approved target contract; no persistent identity registry implemented. |
| CA-Q6 | Full development/test preset: two business Clients with two Accounts each. Production uses explicitly approved real registrations. | Owner approved. This does not replace #194's independent X/Y and five-Principal matrix. |
| CA-Q7 | Idempotent daily authority opening, independent controlled adjustment, explicit business day, TWD only and preserved Account/day uniqueness. | Owner approved with the operation-history, locking and day-boundary requirements below. |
| CA-Q8 | Pre-provision necessary positions for a fixed test universe; dynamic first-BUY support needs atomic creation/application or a proven complete provisioning prerequisite. | Owner approved staged targets. Test-profile PASS does not prove dynamic production capability. |
| CA-Q9 | The owner confirms exact opening content; the controlled tool applies that approved version and records verifiable outcomes. Preserve later allocation of approval responsibilities. | Owner approved the simplified model. Do not require four departments or treat deployment permission as business approval. |
| CA-Q10 | Initial opening relies on authoritative operation receipts; no new Query opening projection. Add distinct opening events/projection only when a real query requirement justifies it. | Owner approved staged target; opening and reservation lifecycle semantics remain distinct. |

These approvals establish target behavior, not runtime acceptance. Monetary amounts, holding
inputs, actual production approvers/operators, trusted approval storage, credentials and transport
remain explicit environment/implementation inputs. Their absence blocks production enablement;
this document does not invent them or reopen already deferred Query IdP/PKI decisions.

## Q7 — opening, adjustment and business day

Keep the current `(account_id, trading_day)` database business key and TWD restriction. A business
request includes currency for validation; this does not authorize a multi-currency redesign.

An administration Request ID must uniquely identify a durable operation record containing its
operation type, approved content, historical result and relevant revisions/before-after values.
The successful operation record and Account authority mutation must commit atomically. Database
uniqueness and content consistency are required; check-then-insert alone is insufficient.

| Input | Required result |
| --- | --- |
| R1 opens 100; R2 later adjusts to 150; identical R1 is resent | Return R1's original successful operation result; leave the current total at 150. |
| R1 is reused with changed approved content | Conflict; no ledger mutation. |
| New R3 attempts to open an existing Account/day | Already exists or Conflict; no implicit adjustment/reset. |
| Same approved opening is retried after commit but before its response was received | Recover the durable operation result; no duplicate effect. |

Historical success and current ledger state must be distinguishable in receipts. Existing plain
INSERT provisioning is not idempotent; however, it is also not evidence that old requests currently
overwrite limits. Old-request regression is an acceptance condition of the new capability.

Adjustment uses the same Account/day row-lock boundary as reservation, with the existing revision
predicate retained. Validate `newTotal >= reserved + utilized`; update total and recomputed
available authority without changing legitimate reserved/utilized amounts. The existing Writer's
`updateLimit()` does not set `limit_total_notional` and must not be described as already supporting
adjustment. A controlled adjustment path and atomic audit record are required.

Lock timeout, deadlock or revision conflict must roll back the attempt. Any allowed bounded retry
re-reads/revalidates current state, rather than reusing a previously computed available amount.
The actual timeout/retry values require a later implementation decision and evidence.

Administration needs an explicit trading-day query. Preserve `getLimits(String accountId)` until
its callers are assessed instead of silently changing its current Taipei-date behavior. Opening a
new day creates a separate approved record; missing today's authority never falls back to yesterday.
Late fills/releases retain their reservation's business day and the existing close/recovery rules.
Account/Symbol positions persist independently of daily limits and must not be zeroed on day change.

## Q8 — first BUY and initial positions

The present first-BUY failure is a reachable source path: reservation can succeed with daily
authority, then fill application throws when Position is absent. The final-event local transaction
encloses effects, inbox claims and progress. Critical delivery can retry and quarantine/pause the
affected TopicPartition; this is not proof that every partition or the entire service stops.
No new deployment reproduction or rollback test was executed during this design review.

- **A, fixed development/test universe:** establish the required zero/approved synthetic positions
  before trading. Repeated initialization must not clear acquired or reserved inventory.
- **B, dynamic universe:** atomically establish/apply a missing position within the Account fill
  transaction, or prove complete provisioning for every permitted BUY instrument. Retain the
  Account/Symbol unique key, exact execution deduplication and existing transaction boundary.

Do not prescribe an UPSERT variant or distributed lock before the selected slice is reviewed.
Tests must cover missing-row first BUY, concurrent distinct executions, duplicate execution,
failure/rollback and repaired retry through the real Matching-to-Account path. A failed application
of an already matched trade is not a new-order rejection and must not be converted into one.
External initial holdings require trusted approved input; automatic BUY position creation does not
establish external assets. A-profile evidence never certifies B's dynamic first-BUY capability.

## Q9 — verifiable opening receipts

The minimum receipt distinguishes Approved, Provisioned, Query Access Verified and FIX Access
Verified. These are individual observed outcomes for the approved request, not a new workflow engine.
Account creation alone must not be reported as all requested access already effective.

Record the intended accounts, Principal/complete FIX SessionID, effective policy identity/version,
verification time and result, without credentials. Query uses a startup snapshot and FIX allowlist
updates require Gateway restart; Secret changes or rollout success alone do not prove adoption.
Failed/partial activation must remain visible and must not restore old/revoked permissions.
Existing Query and FIX operator credentials retain their independent trust purposes.

The approved content must be bound to a trusted approval record. A digest proves content identity,
not approver identity or authority; a caller-supplied `approved: true` is not approval evidence.
The production trust mechanism and staff assignments require separate approval before enablement.

## Q10 — opening visibility

**Initial contract:** Account opening is established by Account Authority's durable operation record.
Query Account Summary remains a reservation-related projection. It does not provide an opening
status or complete Account-existence query. A newly opened Account may remain absent indefinitely
until suitable reservation activity occurs; this is not an assertion of temporary Kafka lag.
Query's concealed missing/unauthorized 404 must not trigger repeat opening or establish failure.

**Optional later contract:** when an explicit consumer requires opening visibility, persist the
opening fact and its Outbox event in the owning local transaction. Query consumes a separate,
idempotent, rebuildable opening projection. Define event identity, state/revision order and replay
boundaries before implementation. Do not reuse reservation events or overwrite reservation summary
semantics. Topic choice is unselected; a distinct business event does not automatically require a
new Kafka topic. Query must not become an admission dependency or authoritative capacity ledger.

## Bounded delivery order

1. Complete #194 under its existing Query Security Phase 1 scope and fixtures.
2. Deliver Account Authority opening/retry, adjustment/day and first-BUY contracts in reviewed
   slices, with real PostgreSQL concurrency and affected trade-path evidence.
3. Deliver durable Client/Account identity and unique ownership, approved CLI/Job operations,
   receipts, then independent FIX/Query access configuration and activation verification.
4. Add opening-query events/projection only when an explicit consumer requirement justifies it.

Steps 2/3 may share a design without becoming one large unreviewed implementation. Limit/Position
rows do not substitute for a durable Account identity/Client ownership record. No new service,
authorization service, distributed lock, cash ledger, currency or Kafka topic is selected here.

## Candidate execution responsibilities

Subject to approval, evaluate a controlled workflow that records the approved Client/Account
relationship, provisions Account-owned daily authority/positions, configures independent FIX
and Query permissions, verifies the result, and only then declares the Account usable.

Fresh environment initialization, restart of retained state, next-trading-day opening and
intentional limit/holding changes require distinct semantics. A restart must not replenish
consumed trading authority. Partial configuration must not implicitly activate trading or
restore revoked access. Cross-service steps must not claim one distributed database transaction.

Account readiness does not open the exchange/session. Preserve approved market artifacts,
instrument/routing rules and explicit trading-readiness/operational barriers. Production
identity, transport, secrets and account approval must be verified under their own contracts;
[ADR-0011](../adr/0011-local-production-like-certification-boundary.md) keeps local evidence
separate from environment promotion.

These are design constraints and candidate responsibilities, not newly implemented capability.

## Evidence and implementation gates

Before implementation, approve ownership semantics, authoritative configuration/records,
administrative trust, the first bounded slice and its acceptance tests. Use versioned Flyway
migrations for approved schema changes and existing Account-owned application seams.

The eventual verification matrix must distinguish source/unit evidence, PostgreSQL behavior,
full-environment order/fill/cancellation evidence, repeat startup/day-transition behavior,
cross-Client denial, credential/grant lifecycle and actual production promotion evidence.
Exact test commands and numerical budgets follow the selected slice rather than this draft.

No database writes, deployments, customer registration, credential issuance for real users,
new Issue publication or changes to existing authorization policy have been performed for
this design work.
